package com.pm.analyticsservice.summarizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the LLM summarizer over real HTTP against a JDK {@link HttpServer} (no broker,
 * no external network, no test dependency). The server stands in for any OpenAI-compatible
 * endpoint. Verifies the happy path parses the chat-completions envelope and that every
 * failure mode degrades to the deterministic {@link TemplateSummarizer}.
 */
class LlmSummarizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesLlmSummaryOnSuccess() throws Exception {
        String contentJson = objectMapper.writeValueAsString(Map.of(
                "summary", "You saved 30% of your income this month, up from last month."));
        String chatBody = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson)))));
        String baseUrl = startServer(200, chatBody);

        FinancialSummary result = summarizer(baseUrl).summarize(data());

        assertThat(result.aiGenerated()).isTrue();
        assertThat(result.text()).contains("saved 30%");
    }

    @Test
    void fallsBackToTemplateOnHttpError() throws Exception {
        String baseUrl = startServer(500, "{\"error\":\"boom\"}");

        FinancialSummary result = summarizer(baseUrl).summarize(data());

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.text()).contains("savings rate of 30.0%");
    }

    @Test
    void fallsBackToTemplateWhenApiUnreachable() {
        FinancialSummary result = summarizer("http://localhost:1").summarize(data());

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.text()).contains("June 2026");
    }

    @Test
    void fallsBackToTemplateOnEmptySummary() throws Exception {
        String contentJson = objectMapper.writeValueAsString(Map.of("summary", ""));
        String chatBody = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", contentJson)))));
        String baseUrl = startServer(200, chatBody);

        FinancialSummary result = summarizer(baseUrl).summarize(data());

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.text()).contains("savings rate of 30.0%");
    }

    private LlmSummarizer summarizer(String baseUrl) {
        SummarizerAiProperties props = new SummarizerAiProperties();
        props.setEnabled(true);
        props.setBaseUrl(baseUrl);
        props.setApiKey("test-key");
        props.setTimeoutMs(2000);
        return new LlmSummarizer(props, new TemplateSummarizer(), new SimpleMeterRegistry());
    }

    private MonthlySummaryData data() {
        return new MonthlySummaryData(
                "2026-06", "USD",
                new BigDecimal("1000.00"), new BigDecimal("700.00"), new BigDecimal("300.00"),
                30.0, 0.0, List.of());
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
}
