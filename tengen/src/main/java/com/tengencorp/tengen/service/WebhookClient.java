package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Performs exactly one HTTP delivery attempt. Retry policy and durable state
 * transitions belong to the background worker.
 */
@Service
public class WebhookClient {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WebhookClient(ObjectMapper objectMapper) {
        this(objectMapper, new WebhookDeliveryProperties());
    }

    public WebhookClient(ObjectMapper objectMapper, WebhookDeliveryProperties properties) {
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        this.restClient = RestClient.builder()
            .requestFactory(factory)
            .build();
    }

    public WebhookDeliveryResult deliverOnce(String callbackUrl, Map<String, Object> payload) {
        long startedAt = System.nanoTime();
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return WebhookDeliveryResult.failure(
                false, null, truncate(errorMessage(e)), elapsedMs(startedAt));
        }

        try {
            var response = restClient.post()
                .uri(callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            int statusCode = response.getStatusCode().value();
            if (statusCode >= 200 && statusCode < 300) {
                return WebhookDeliveryResult.success(statusCode, elapsedMs(startedAt));
            }
            return WebhookDeliveryResult.failure(
                statusCode == 408 || statusCode == 429 || statusCode >= 500,
                statusCode,
                "Unexpected HTTP status",
                elapsedMs(startedAt));
        } catch (RestClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            boolean retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
            return WebhookDeliveryResult.failure(
                retryable, statusCode, truncate(e.getStatusText()), elapsedMs(startedAt));
        } catch (Exception e) {
            return WebhookDeliveryResult.failure(
                true, null, truncate(errorMessage(e)), elapsedMs(startedAt));
        }
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
