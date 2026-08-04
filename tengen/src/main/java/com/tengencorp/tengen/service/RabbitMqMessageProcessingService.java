package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.EventRequest;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.entity.RabbitMqMessageReceipt;
import com.tengencorp.tengen.exception.RabbitMqPermanentMessageException;
import com.tengencorp.tengen.repository.RabbitMqMessageReceiptRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Validates one raw delivery and joins its receipt to the event transaction. */
@Service
public class RabbitMqMessageProcessingService {

    private final RabbitMqMessageReceiptRepository receiptRepository;
    private final EventService eventService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public RabbitMqMessageProcessingService(
            RabbitMqMessageReceiptRepository receiptRepository,
            EventService eventService,
            ObjectMapper objectMapper,
            Validator validator) {
        this.receiptRepository = receiptRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Transactional
    public DeliveryResult processOnce(RabbitMqConnector connector,
                                      MessageProperties properties,
                                      byte[] body) {
        String messageId = requiredMessageId(properties);
        String contentType = properties.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase().startsWith("application/json")) {
            throw new RabbitMqPermanentMessageException("CONFLICTING_CONTENT_TYPE",
                "The RabbitMQ content type is not application/json");
        }
        if (body == null || body.length > connector.getMaxBodyBytes()) {
            throw new RabbitMqPermanentMessageException("BODY_SIZE_EXCEEDED",
                "The RabbitMQ message exceeds the configured body limit");
        }

        EventRequest request;
        try {
            request = objectMapper.readValue(body, EventRequest.class);
        } catch (Exception exception) {
            throw new RabbitMqPermanentMessageException("MALFORMED_JSON",
                "The RabbitMQ message is not valid JSON");
        }
        Set<ConstraintViolation<EventRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()
                || request.type().length() > 100
                || request.source().length() > 100) {
            throw new RabbitMqPermanentMessageException("VALIDATION_FAILED",
                "The RabbitMQ event does not satisfy the event contract");
        }

        int inserted = receiptRepository.insertIfAbsent(
            connector.getId(),
            connector.getQueueName(),
            messageId,
            bounded(properties.getReceivedExchange()),
            bounded(properties.getReceivedRoutingKey()),
            connector.getApiKey().getId());
        if (inserted == 0) {
            return new DeliveryResult(true, null);
        }

        boolean applyWatermark = RabbitMqWatermarkPolicy.shouldApply(properties);
        var result = eventService.processRabbitMq(
            request, connector.getApiKey().getId(), connector, applyWatermark);
        RabbitMqMessageReceipt receipt = receiptRepository
            .findByConnector_IdAndQueueNameAndMessageId(
                connector.getId(), connector.getQueueName(), messageId)
            .orElseThrow(() -> new IllegalStateException("RabbitMQ receipt was not created"));
        receipt.setEvent(result.event());
        receiptRepository.save(receipt);
        return new DeliveryResult(false, result.event());
    }

    private String requiredMessageId(MessageProperties properties) {
        String messageId = properties.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            throw new RabbitMqPermanentMessageException("MESSAGE_ID_MISSING",
                "The RabbitMQ message_id property is required");
        }
        String normalized = messageId.trim();
        if (normalized.length() > 255) {
            throw new RabbitMqPermanentMessageException("MESSAGE_ID_INVALID",
                "The RabbitMQ message_id property is too long");
        }
        return normalized;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return normalized.substring(0, Math.min(255, normalized.length()));
    }

    public record DeliveryResult(boolean deduplicated, com.tengencorp.tengen.entity.Event event) {
    }
}
