package com.tengencorp.tengen.service;

import com.rabbitmq.client.Channel;
import com.tengencorp.tengen.entity.RabbitMqConnector;
import com.tengencorp.tengen.entity.RabbitMqConnectorRuntimeState;
import com.tengencorp.tengen.exception.RabbitMqConnectorException;
import com.tengencorp.tengen.exception.RabbitMqPermanentMessageException;
import com.tengencorp.tengen.repository.RabbitMqConnectorRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the single dynamic listener and keeps volatile runtime state out of PostgreSQL. */
@Component
public class RabbitMqRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqRuntimeManager.class);

    private final RabbitMqConnectorRepository connectorRepository;
    private final RabbitMqConnectionService connectionService;
    private final RabbitMqMessageProcessingService processingService;
    private final RabbitMqDeadLetterService deadLetterService;
    private final long listenerShutdownTimeoutMs;
    private final int listenerConsumerCount;
    private final int listenerPrefetchCount;
    private final Map<Long, RuntimeHandle> handles = new ConcurrentHashMap<>();
    private final Counter acceptedCounter;
    private final Counter deduplicatedCounter;
    private final Counter retriedCounter;
    private final Counter deadLetteredCounter;
    private final Counter watermarkAppliedCounter;
    private final Counter watermarkSkippedCounter;

    public RabbitMqRuntimeManager(
            RabbitMqConnectorRepository connectorRepository,
            RabbitMqConnectionService connectionService,
            RabbitMqMessageProcessingService processingService,
            RabbitMqDeadLetterService deadLetterService,
            MeterRegistry meterRegistry,
            @Value("${tengen.rabbitmq.shutdown-timeout-ms:5000}") long listenerShutdownTimeoutMs,
            @Value("${tengen.rabbitmq.consumers:1}") int listenerConsumerCount,
            @Value("${tengen.rabbitmq.prefetch:1}") int listenerPrefetchCount) {
        this.connectorRepository = connectorRepository;
        this.connectionService = connectionService;
        this.processingService = processingService;
        this.deadLetterService = deadLetterService;
        this.listenerShutdownTimeoutMs = Math.max(500, Math.min(30_000, listenerShutdownTimeoutMs));
        this.listenerConsumerCount = bounded(listenerConsumerCount, 1, 32);
        this.listenerPrefetchCount = bounded(listenerPrefetchCount, 1, 1_000);
        this.acceptedCounter = Counter.builder("tengen.rabbitmq.messages")
            .tag("result", "accepted").description("RabbitMQ messages accepted").register(meterRegistry);
        this.deduplicatedCounter = Counter.builder("tengen.rabbitmq.messages")
            .tag("result", "deduplicated").description("RabbitMQ redeliveries skipped").register(meterRegistry);
        this.retriedCounter = Counter.builder("tengen.rabbitmq.messages")
            .tag("result", "retried").description("RabbitMQ processing retries").register(meterRegistry);
        this.deadLetteredCounter = Counter.builder("tengen.rabbitmq.messages")
            .tag("result", "dead_lettered").description("RabbitMQ messages dead-lettered").register(meterRegistry);
        this.watermarkAppliedCounter = Counter.builder("tengen.rabbitmq.watermark")
            .tag("result", "applied").description("RabbitMQ messages processed with watermarking")
            .register(meterRegistry);
        this.watermarkSkippedCounter = Counter.builder("tengen.rabbitmq.watermark")
            .tag("result", "skipped").description("RabbitMQ messages processed without watermarking")
            .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        connectorRepository.findFirstByOrderByIdAsc().ifPresent(connector -> {
            if (!connector.isEnabled()) return;
            try {
                start(connector);
            } catch (RabbitMqConnectorException exception) {
                log.warn("RabbitMQ connector paused on startup: connector={}, category={}",
                    connector.getConnectorKey(), exception.category());
            } catch (Exception exception) {
                setState(connector.getId(), RabbitMqConnectorRuntimeState.ERROR,
                    "STARTUP_FAILED");
                log.warn("RabbitMQ connector paused on startup: connector={}, category=STARTUP_FAILED",
                    connector.getConnectorKey());
            }
        });
    }

    public synchronized void start(RabbitMqConnector connector) {
        if (connector.getId() == null) {
            throw new RabbitMqConnectorException("CONNECTOR_NOT_SAVED", "The RabbitMQ connector is not saved");
        }
        if (connector.getLastTestedVersion() == null
                || connector.getLastTestedVersion() != connector.getConfigurationVersion()
                || !Boolean.TRUE.equals(connector.getLastTestSucceeded())) {
            throw new RabbitMqConnectorException("TEST_REQUIRED",
                "The current RabbitMQ configuration must pass a connection test before it can start");
        }
        if (connector.getApiKey() == null || !connector.getApiKey().isActive()
                || (connector.getApiKey().getExpiresAt() != null
                    && !connector.getApiKey().getExpiresAt().isAfter(Instant.now()))) {
            throw new RabbitMqConnectorException("API_KEY_REVOKED",
                "The connector API key is inactive or expired");
        }
        stopInternal(connector.getId(), false);
        setState(connector.getId(), RabbitMqConnectorRuntimeState.CONNECTING, null);
        CachingConnectionFactory factory = null;
        SimpleMessageListenerContainer container = null;
        try {
            factory = connectionService.createConnectionFactory(connector);
            container = new SimpleMessageListenerContainer(factory);
            container.setQueueNames(connector.getQueueName());
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setPrefetchCount(listenerPrefetchCount);
            container.setConcurrentConsumers(listenerConsumerCount);
            container.setMaxConcurrentConsumers(listenerConsumerCount);
            container.setAutoStartup(false);
            container.setAutoDeclare(false);
            container.setMissingQueuesFatal(true);
            container.setShutdownTimeout(listenerShutdownTimeoutMs);
            SimpleMessageListenerContainer listener = container;
            container.setErrorHandler(error -> pauseAsync(connector, listener, "LISTENER_ERROR"));
            container.setMessageListener((org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)
                (message, channel) -> handleDelivery(connector, listener, message, channel));
            handles.put(connector.getId(), new RuntimeHandle(container, factory,
                RabbitMqConnectorRuntimeState.CONNECTING, null, Instant.now()));
            container.start();
            setState(connector.getId(), RabbitMqConnectorRuntimeState.RUNNING, null);
        } catch (RabbitMqConnectorException exception) {
            closeResources(container, factory);
            setState(connector.getId(), RabbitMqConnectorRuntimeState.PAUSED, exception.category());
            throw exception;
        } catch (Exception exception) {
            closeResources(container, factory);
            setState(connector.getId(), RabbitMqConnectorRuntimeState.ERROR, "CONNECTION_FAILED");
            throw new RabbitMqConnectorException("CONNECTION_FAILED",
                "RabbitMQ listener could not start", exception);
        }
    }

    public synchronized void stop(Long connectorId) {
        stopInternal(connectorId, true);
    }

    public RuntimeStatus status(Long connectorId, boolean enabled) {
        RuntimeHandle handle = connectorId == null ? null : handles.get(connectorId);
        if (handle != null) {
            return new RuntimeStatus(handle.state(), handle.errorCategory(), handle.lastTransitionAt());
        }
        return new RuntimeStatus(
            enabled ? RabbitMqConnectorRuntimeState.PAUSED : RabbitMqConnectorRuntimeState.DISABLED,
            enabled ? "NOT_RUNNING" : null,
            null);
    }

    public boolean isRunning() {
        return handles.values().stream()
            .anyMatch(handle -> handle.state() == RabbitMqConnectorRuntimeState.RUNNING);
    }

    public void markTesting(Long connectorId) {
        if (connectorId != null) {
            setState(connectorId, RabbitMqConnectorRuntimeState.TESTING, null);
        }
    }

    public void markTestFinished(Long connectorId, boolean successful, String category) {
        if (connectorId == null) return;
        RuntimeHandle current = handles.get(connectorId);
        if (current != null && current.container() != null
                && current.state() == RabbitMqConnectorRuntimeState.RUNNING) {
            return;
        }
        setState(connectorId,
            successful ? RabbitMqConnectorRuntimeState.DISABLED : RabbitMqConnectorRuntimeState.ERROR,
            successful ? null : category);
    }

    public synchronized void pause(RabbitMqConnector connector, String category) {
        RuntimeHandle handle = handles.get(connector.getId());
        if (handle != null) {
            setState(connector.getId(), RabbitMqConnectorRuntimeState.PAUSED, category);
            pauseAsync(connector, handle.container(), category);
        } else {
            setState(connector.getId(), RabbitMqConnectorRuntimeState.PAUSED, category);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        handles.keySet().forEach(id -> stopInternal(id, false));
    }

    private void handleDelivery(RabbitMqConnector connector,
                                SimpleMessageListenerContainer container,
                                Message message,
                                Channel channel) throws Exception {
        long started = System.nanoTime();
        int attempts = Math.max(1, connector.getRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                RabbitMqMessageProcessingService.DeliveryResult result = processingService.processOnce(
                    connector, message.getMessageProperties(), message.getBody());
                if (result.deduplicated()) {
                    deduplicatedCounter.increment();
                } else {
                    acceptedCounter.increment();
                    if (Boolean.FALSE.equals(result.event().getWatermarkApplied())) {
                        watermarkSkippedCounter.increment();
                    } else {
                        watermarkAppliedCounter.increment();
                    }
                }
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            } catch (RabbitMqPermanentMessageException exception) {
                try {
                    deadLetter(connector, message, exception.category());
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                    deadLetteredCounter.increment();
                } catch (RabbitMqConnectorException deadLetterFailure) {
                    pause(connector, deadLetterFailure.category());
                } catch (Exception deadLetterFailure) {
                    pause(connector, "DEAD_LETTER_UNAVAILABLE");
                }
                return;
            } catch (RabbitMqConnectorException exception) {
                pause(connector, exception.category());
                return;
            } catch (Exception exception) {
                if (attempt >= attempts) break;
                retriedCounter.increment();
                sleepBeforeRetry(connector, attempt);
            }
        }

        try {
            deadLetter(connector, message, "PROCESSING_RETRIES_EXHAUSTED");
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            deadLetteredCounter.increment();
        } catch (RabbitMqConnectorException exception) {
            pause(connector, exception.category());
        } catch (Exception exception) {
            pause(connector, "DEAD_LETTER_UNAVAILABLE");
        }
        log.warn("RabbitMQ delivery ended: connector={}, attempt={}, durationMs={}",
            connector.getConnectorKey(), attempts,
            (System.nanoTime() - started) / 1_000_000);
    }

    private void deadLetter(RabbitMqConnector connector, Message message, String category) {
        var properties = message.getMessageProperties();
        deadLetterService.publish(connector, message.getBody(), properties.getMessageId(),
            properties.getContentType(), category);
    }

    private void sleepBeforeRetry(RabbitMqConnector connector, int attempt) {
        double calculated = connector.getRetryInitialDelayMs()
            * Math.pow(connector.getRetryMultiplier(), Math.max(0, attempt - 1));
        long delay = Math.min(connector.getRetryMaxDelayMs(), Math.max(0, (long) calculated));
        if (delay == 0) return;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RabbitMqConnectorException("PROCESSING_INTERRUPTED",
                "RabbitMQ processing was interrupted", interrupted);
        }
    }

    private synchronized void stopInternal(Long connectorId, boolean setDisabled) {
        if (connectorId == null) return;
        RuntimeHandle handle = handles.remove(connectorId);
        if (handle != null) {
            closeResources(handle.container(), handle.factory());
        }
        if (setDisabled) {
            setState(connectorId, RabbitMqConnectorRuntimeState.DISABLED, null);
        }
    }

    private void pauseAsync(RabbitMqConnector connector,
                            SimpleMessageListenerContainer container,
                            String category) {
        setState(connector.getId(), RabbitMqConnectorRuntimeState.PAUSED, category);
        CompletableFuture.runAsync(() -> {
            try {
                if (container.isRunning()) container.stop();
            } catch (Exception ignored) { }
            RuntimeHandle current = handles.get(connector.getId());
            if (current != null && current.container() == container) {
                if (current.factory() != null) {
                    try { current.factory().destroy(); } catch (Exception ignored) { }
                }
                handles.put(connector.getId(), new RuntimeHandle(null, null,
                    RabbitMqConnectorRuntimeState.PAUSED, category, Instant.now()));
            }
        });
    }

    private synchronized void setState(Long connectorId,
                                        RabbitMqConnectorRuntimeState state,
                                        String category) {
        RuntimeHandle current = handles.get(connectorId);
        if (current == null) {
            handles.put(connectorId, new RuntimeHandle(null, null, state, category, Instant.now()));
            return;
        }
        handles.put(connectorId, new RuntimeHandle(current.container(), current.factory(), state,
            category, Instant.now()));
    }

    private void closeResources(SimpleMessageListenerContainer container,
                                CachingConnectionFactory factory) {
        if (container != null) {
            try { container.stop(); } catch (Exception ignored) { }
            try { container.destroy(); } catch (Exception ignored) { }
        }
        if (factory != null) {
            try { factory.destroy(); } catch (Exception ignored) { }
        }
    }

    private int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RuntimeHandle(SimpleMessageListenerContainer container,
                                 CachingConnectionFactory factory,
                                 RabbitMqConnectorRuntimeState state,
                                 String errorCategory,
                                 Instant lastTransitionAt) {
    }

    public record RuntimeStatus(RabbitMqConnectorRuntimeState state,
                                String errorCategory,
                                Instant lastTransitionAt) {
    }
}
