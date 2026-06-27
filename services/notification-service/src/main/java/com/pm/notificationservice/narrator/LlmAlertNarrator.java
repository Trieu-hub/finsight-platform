package com.pm.notificationservice.narrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.notificationservice.event.RiskDetectedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LLM-backed {@link AlertNarrator} that phrases the alert more naturally than the fixed
 * templates. It speaks the <b>OpenAI-compatible</b> Chat Completions protocol, so the provider
 * is just configuration ({@code finsight.narrator.ai.base-url} + {@code model}). The default is
 * <b>Groq</b> (free tier, Llama models); OpenAI, OpenRouter, or a local Ollama work unchanged by
 * pointing the base URL elsewhere.
 *
 * <p>It is a drop-in behind the {@link AlertNarrator} seam: created only when
 * {@code finsight.narrator.ai.enabled=true} and marked {@link Primary} so it wins injection over
 * {@link TemplateNarrator}, which it keeps as the safe fallback.
 *
 * <p><b>It never fails the pipeline.</b> Any error — timeout, non-2xx, malformed JSON — is caught
 * and the rule-based narrator answers instead, so a flaky external API can never block a
 * notification or trigger a Kafka retry storm. Only {@code riskType}/{@code riskSeverity} are
 * sent upstream; no userId, email, or amount (no PII).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "finsight.narrator.ai", name = "enabled", havingValue = "true")
public class LlmAlertNarrator implements AlertNarrator {

    private static final Logger log = LoggerFactory.getLogger(LlmAlertNarrator.class);

    private static final String SYSTEM_PROMPT = """
            You write short in-app financial risk alerts for an end user.
            Respond with a single JSON object: {"title": string, "message": string}.
            The title is at most 60 characters; the message is one or two calm, plain sentences \
            (at most 200 characters) telling the user what was detected and to review it if it \
            looks unfamiliar. Do not invent amounts, dates, or account details. English only.""";

    private final NarratorAiProperties props;
    private final TemplateNarrator fallback;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final Counter aiSuccess;
    private final Counter aiFallback;

    // TemplateNarrator is injected by its concrete type so there is no ambiguity with this
    // bean (both are AlertNarrator). It is the safe fallback for every failure path.
    public LlmAlertNarrator(NarratorAiProperties props,
                            TemplateNarrator fallback,
                            MeterRegistry meterRegistry) {
        this.props = props;
        this.fallback = fallback;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .build();

        this.aiSuccess = Counter.builder("finsight.notifications.ai.success")
                .description("RiskDetected alerts narrated by the LLM")
                .register(meterRegistry);
        this.aiFallback = Counter.builder("finsight.notifications.ai.fallback")
                .description("Alerts that fell back to the rule-based narrator after an LLM error")
                .register(meterRegistry);
    }

    @Override
    public AlertContent narrate(RiskDetectedEvent event) {
        try {
            AlertContent content = callLlm(event);
            aiSuccess.increment();
            return content;
        } catch (Exception ex) {
            // Any failure degrades to the deterministic narrator; never propagate.
            log.warn("LLM narration failed for riskType={}, falling back to template: {}",
                    event.riskType(), ex.toString());
            aiFallback.increment();
            return fallback.narrate(event);
        }
    }

    private AlertContent callLlm(RiskDetectedEvent event) throws Exception {
        // No PII: only the risk metadata is sent upstream.
        String userPrompt = "riskType=" + event.riskType() + ", severity=" + event.riskSeverity();

        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "max_tokens", props.getMaxTokens(),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)));

        String raw = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(raw);
        String contentJson = root.path("choices").path(0).path("message").path("content").asText();
        JsonNode parsed = objectMapper.readTree(contentJson);
        String title = parsed.path("title").asText("");
        String message = parsed.path("message").asText("");

        if (title.isBlank() || message.isBlank()) {
            throw new IllegalStateException("LLM returned an empty title or message");
        }
        return new AlertContent(TemplateNarrator.TYPE_RISK_ALERT, title, message);
    }
}
