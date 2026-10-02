package com.arthadhruva.riskengine.assistant;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Thin HTTP client for the LiteLLM proxy (see docker-compose.yml) -- an OpenAI-compatible REST
 * API in front of whichever real provider {@code litellm-config.yaml} maps {@code
 * litellm.model} to. Deliberately talks the plain OpenAI chat-completions JSON shape via a local
 * record pair rather than depending on any provider SDK, since the whole point of routing through
 * LiteLLM is that the Java side never needs to know which real provider is behind it.
 *
 * <p>Fails closed to {@link Optional#empty()}, not an exception: a circuit-open state, a network
 * error, or LiteLLM itself returning an error (e.g. its upstream provider has no API key
 * configured) all collapse to "the assistant has nothing to say right now" -- {@link
 * AssistantService} turns that into a clear, honest user-facing message rather than a 500. Same
 * fail-open philosophy as {@code CacheService}.
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** Bounds the cost and length of one answer. */
    private static final int MAX_ANSWER_TOKENS = 700;
    /** Low: answers should restate the facts in the context, not improvise. */
    private static final double TEMPERATURE = 0.2;

    private final RestClient restClient;
    private final String model;

    public LlmClient(@Value("${litellm.base-url}") String baseUrl,
                      @Value("${litellm.model}") String model,
                      @Value("${litellm.api-key}") String apiKey) {
        this.model = model;
        // Without timeouts a stalled provider would hold a request thread (and a bulkhead slot) forever.
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(3));
        timeouts.setReadTimeout(Duration.ofSeconds(30));
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    public String model() {
        return model;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "completeFallback")
    @Retry(name = "llm")
    @Bulkhead(name = "llm")
    public Optional<String> complete(String systemPrompt, String userMessage) {
        ChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatCompletionRequest(model, List.of(
                        new ChatMessage("system", systemPrompt),
                        new ChatMessage("user", userMessage)), MAX_ANSWER_TOKENS, TEMPERATURE))
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.choices().get(0).message()).map(ChatMessage::content);
    }

    @SuppressWarnings("unused")   // called by Resilience4j by name
    private Optional<String> completeFallback(String systemPrompt, String userMessage, Throwable t) {
        log.warn("LLM call failed (circuit open, network error, or provider error)", t);
        return Optional.empty();
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatCompletionRequest(String model, List<ChatMessage> messages,
                                         @JsonProperty("max_tokens") int maxTokens, double temperature) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
        private record Choice(ChatMessage message) {
        }
    }
}
