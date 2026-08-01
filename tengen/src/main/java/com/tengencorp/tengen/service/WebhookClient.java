package com.tengencorp.tengen.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Best-effort webhook delivery. POSTs the full evaluation result to a rule's
 * callbackUrl with up to 3 retries and a short backoff. Failures are logged
 * and never surface to the caller.
 */
@Service
public class WebhookClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WebhookClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
            .requestFactory(factory)
            .build();
    }

    /**
     * Attempt to deliver the payload. Returns true on success (any attempt), false otherwise.
     */
    public boolean deliver(String callbackUrl, Map<String, Object> payload) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(payload);
                restClient.post()
                    .uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
                return true;
            } catch (Exception e) {
                log.warn("Webhook delivery attempt {}/{} to {} failed: {}",
                    attempt, MAX_ATTEMPTS, callbackUrl, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleep(backoff(attempt));
                }
            }
        }
        return false;
    }

    private static long backoff(int attempt) {
        return (long) Math.pow(2, attempt) * 200L; // 400ms, 800ms
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
