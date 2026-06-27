package com.pm.notificationservice.narrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the LLM narrator over real HTTP against a JDK {@link HttpServer} (no broker, no
 * external network, no test dependency). The server stands in for any OpenAI-compatible endpoint
 * (Groq, OpenAI, OpenRouter, Ollama). Verifies the happy path parses the chat-completions
 * envelope, and that every failure mode degrades to the deterministic {@link TemplateNarrator}.
 */
class LlmAlertNarratorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesLlmTitleAndMessageOnSuccess() throws Exception {
        String contentJson = objectMapper.writeValueAsString(Map.of(
                "title", "Big spend flagged",
                "message", "We noticed a large expense. Review it if it wasn't you."));
        String chatBody = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson)))));
        String baseUrl = startServer(200, chatBody);

        AlertContent content = narrator(baseUrl).narrate(event("HIGH_AMOUNT_EXPENSE", "HIGH"));

        assertThat(content.type()).isEqualTo(TemplateNarrator.TYPE_RISK_ALERT);
        assertThat(content.title()).isEqualTo("Big spend flagged");
        assertThat(content.message()).contains("large expense");
    }

    @Test
    void fallsBackToTemplateOnHttpError() throws Exception {
        String baseUrl = startServer(500, "{\"error\":\"boom\"}");

        AlertContent content = narrator(baseUrl).narrate(event("HIGH_AMOUNT_EXPENSE", "HIGH"));

        // TemplateNarrator's deterministic wording for this risk type.
        assertThat(content.title()).isEqualTo("Large expense detected");
    }

    @Test
    void fallsBackToTemplateWhenApiUnreachable() {
        // Nothing is listening here, so the connect fails fast and we degrade to the template.
        AlertContent content = narrator("http://localhost:1").narrate(event("RAPID_SPENDING", "MEDIUM"));

        assertThat(content.title()).isEqualTo("Rapid spending detected");
    }

    @Test
    void fallsBackToTemplateOnEmptyLlmFields() throws Exception {
        String contentJson = objectMapper.writeValueAsString(Map.of("title", "", "message", ""));
        String chatBody = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson)))));
        String baseUrl = startServer(200, chatBody);

        AlertContent content = narrator(baseUrl).narrate(event("LARGE_DAILY_SPEND", "HIGH"));

        assertThat(content.title()).isEqualTo("High spending today");
    }

    private LlmAlertNarrator narrator(String baseUrl) {
        NarratorAiProperties props = new NarratorAiProperties();
        props.setEnabled(true);
        props.setBaseUrl(baseUrl);
        props.setApiKey("test-key");
        props.setTimeoutMs(2000);
        return new LlmAlertNarrator(props, new TemplateNarrator(), new SimpleMeterRegistry());
    }

    private String startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private RiskDetectedEvent event(String riskType, String severity) {
        return new RiskDetectedEvent(UUID.randomUUID(), "RiskDetected", "2026-06-26T10:00:00Z",
                42L, UUID.randomUUID(), riskType, severity);
    }
}
