package com.tengencorp.tengen.config;

import com.tengencorp.tengen.entity.RabbitMqConnectorRuntimeState;
import com.tengencorp.tengen.repository.RabbitMqConnectorRepository;
import com.tengencorp.tengen.service.RabbitMqRuntimeManager;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Safe connector health; broker credentials and targets are never included. */
@Component("rabbitmqConnector")
public class RabbitMqConnectorHealthIndicator implements HealthIndicator {

    private final RabbitMqConnectorRepository connectorRepository;
    private final RabbitMqRuntimeManager runtimeManager;

    public RabbitMqConnectorHealthIndicator(RabbitMqConnectorRepository connectorRepository,
                                             RabbitMqRuntimeManager runtimeManager) {
        this.connectorRepository = connectorRepository;
        this.runtimeManager = runtimeManager;
    }

    @Override
    public Health health() {
        return connectorRepository.findFirstByOrderByIdAsc()
            .map(connector -> {
                var status = runtimeManager.status(connector.getId(), connector.isEnabled());
                if (!connector.isEnabled() || status.state() == RabbitMqConnectorRuntimeState.DISABLED) {
                    return Health.up().withDetail("state", RabbitMqConnectorRuntimeState.DISABLED).build();
                }
                if (status.state() == RabbitMqConnectorRuntimeState.RUNNING) {
                    return Health.up().withDetail("state", RabbitMqConnectorRuntimeState.RUNNING).build();
                }
                return Health.unknown()
                    .withDetail("state", status.state())
                    .withDetail("category", status.errorCategory() == null ? "NOT_RUNNING" : status.errorCategory())
                    .build();
            })
            .orElseGet(() -> Health.up().withDetail("state", RabbitMqConnectorRuntimeState.DISABLED).build());
    }
}
