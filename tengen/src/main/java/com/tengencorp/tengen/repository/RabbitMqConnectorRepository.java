package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RabbitMqConnector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;

public interface RabbitMqConnectorRepository extends JpaRepository<RabbitMqConnector, Long> {

    @EntityGraph(attributePaths = "apiKey")
    Optional<RabbitMqConnector> findByConnectorKey(String connectorKey);

    @Override
    @EntityGraph(attributePaths = "apiKey")
    Optional<RabbitMqConnector> findById(Long id);

    @EntityGraph(attributePaths = "apiKey")
    Optional<RabbitMqConnector> findFirstByOrderByIdAsc();
}
