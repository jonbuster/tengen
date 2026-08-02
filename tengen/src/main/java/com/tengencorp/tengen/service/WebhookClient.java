package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** Performs one signed, redirect-free HTTP delivery attempt. */
@Service
public class WebhookClient {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final RestClient restClient;
    private final ObjectWriter payloadWriter;
    private final WebhookDestinationValidator destinationValidator;
    private final byte[] signingSecret;

    public WebhookClient(ObjectMapper objectMapper, WebhookDeliveryProperties properties,
                         WebhookDestinationValidator destinationValidator) {
        this.payloadWriter = objectMapper.writer()
            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.destinationValidator = destinationValidator;
        this.signingSecret = properties.getSigningSecret().getBytes(StandardCharsets.UTF_8);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public WebhookDeliveryResult deliverOnce(String callbackUrl, Map<String, Object> payload,
                                               Long deliveryId, Instant createdAt) {
        long startedAt = System.nanoTime();
        String body;
        try {
            body = payloadWriter.writeValueAsString(payload);
        } catch (Exception exception) {
            return WebhookDeliveryResult.failure(
                false, null, truncate(errorMessage(exception)), elapsedMs(startedAt));
        }

        final String timestamp = String.valueOf(createdAt.getEpochSecond());
        final String signature;
        try {
            destinationValidator.validateForDelivery(callbackUrl);
            signature = signature(timestamp, body);
        } catch (WebhookDestinationValidator.DestinationResolutionException exception) {
            return WebhookDeliveryResult.failure(
                true, null, truncate(exception.getMessage()), elapsedMs(startedAt));
        } catch (IllegalArgumentException exception) {
            return WebhookDeliveryResult.failure(
                false, null, truncate(exception.getMessage()), elapsedMs(startedAt));
        }

        try {
            var response = restClient.post()
                .uri(callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Tengen-Delivery-Id", String.valueOf(deliveryId))
                .header("X-Tengen-Timestamp", timestamp)
                .header("X-Tengen-Signature", signature)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            int statusCode = response.getStatusCode().value();
            if (statusCode >= 200 && statusCode < 300) {
                return WebhookDeliveryResult.success(statusCode, elapsedMs(startedAt));
            }
            return WebhookDeliveryResult.failure(
                statusCode == 408 || statusCode == 429 || statusCode >= 500,
                statusCode, "Unexpected HTTP status", elapsedMs(startedAt));
        } catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            boolean retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500;
            return WebhookDeliveryResult.failure(
                retryable, statusCode, truncate(exception.getStatusText()), elapsedMs(startedAt));
        } catch (Exception exception) {
            return WebhookDeliveryResult.failure(
                true, null, truncate(errorMessage(exception)), elapsedMs(startedAt));
        }
    }

    /** Compatibility entry point retained for callers compiled against the pre-outbox contract. */
    @Deprecated(forRemoval = true)
    public WebhookDeliveryResult deliverOnce(String callbackUrl, Map<String, Object> payload) {
        return deliverOnce(callbackUrl, payload, 0L, Instant.now());
    }

    String signature(String timestamp, String body) {
        return "v1=" + sign(timestamp + "." + body);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign webhook", exception);
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
