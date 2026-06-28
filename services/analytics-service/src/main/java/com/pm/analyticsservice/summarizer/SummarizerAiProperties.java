package com.pm.analyticsservice.summarizer;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional LLM-backed {@link Summarizer}. Disabled by default:
 * with {@code enabled=false} no LLM bean is created and the deterministic
 * {@link TemplateSummarizer} is used. The provider is any OpenAI-compatible Chat
 * Completions API; the default is Groq (free tier).
 *
 * <p>Unlike notification-service's narrator, the monthly summary by nature sends
 * AGGREGATED figures (totals + category names) to the model so it can describe them.
 * It never sends a userId, email, or any individual transaction — no identity leaves
 * the service.
 */
@ConfigurationProperties(prefix = "finsight.summarizer.ai")
@Getter
@Setter
public class SummarizerAiProperties {

    /** Master switch. When false the LLM summarizer bean is not created. */
    private boolean enabled = false;

    /** OpenAI-compatible base URL. Default Groq; swap for OpenAI/OpenRouter/Ollama. */
    private String baseUrl = "https://api.groq.com/openai/v1";

    /** API key; read from the LLM_API_KEY env var via application.yml. */
    private String apiKey = "";

    /** Chat model. Groq's llama-3.1-8b-instant is free and fast enough for a short summary. */
    private String model = "llama-3.1-8b-instant";

    /** Hard cap on the call so a slow API never stalls the request thread. */
    private long timeoutMs = 6000;

    /** Output cap — two or three sentences need little. */
    private int maxTokens = 300;
}
