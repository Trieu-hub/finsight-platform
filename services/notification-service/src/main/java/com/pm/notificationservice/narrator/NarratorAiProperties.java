package com.pm.notificationservice.narrator;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional LLM-backed {@link AlertNarrator}. Disabled by default:
 * with {@code enabled=false} no LLM bean is created and the rule-based {@link TemplateNarrator}
 * is used. The provider is any OpenAI-compatible Chat Completions API; the default is Groq
 * (free tier). Only the metadata of a detected risk (type + severity) is sent — never a userId,
 * email, or amount — so no PII leaves the service.
 */
@ConfigurationProperties(prefix = "finsight.narrator.ai")
@Getter
@Setter
public class NarratorAiProperties {

    /** Master switch. When false the LLM narrator bean is not created. */
    private boolean enabled = false;

    /** OpenAI-compatible base URL. Default Groq; swap for OpenAI/OpenRouter/Ollama as needed. */
    private String baseUrl = "https://api.groq.com/openai/v1";

    /** API key; read from the LLM_API_KEY env var via application.yml. */
    private String apiKey = "";

    /** Chat model. Groq's llama-3.1-8b-instant is free and fast — enough for a two-line alert. */
    private String model = "llama-3.1-8b-instant";

    /** Hard cap on the OpenAI call so a slow API never stalls the Kafka consumer. */
    private long timeoutMs = 6000;

    /** Output cap — a title plus one or two sentences needs very little. */
    private int maxTokens = 200;
}
