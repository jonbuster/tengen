package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RabbitMqMessageReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RabbitMqMessageReceiptRepository extends JpaRepository<RabbitMqMessageReceipt, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO rabbitmq_message_receipts
            (connector_id, queue_name, message_id, source_exchange, routing_key,
             api_key_id, processed_at)
        VALUES (:connectorId, :queueName, :messageId, :sourceExchange, :routingKey,
                :apiKeyId, now())
        ON CONFLICT (connector_id, queue_name, message_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("connectorId") Long connectorId,
                       @Param("queueName") String queueName,
                       @Param("messageId") String messageId,
                       @Param("sourceExchange") String sourceExchange,
                       @Param("routingKey") String routingKey,
                       @Param("apiKeyId") Long apiKeyId);

    Optional<RabbitMqMessageReceipt> findByConnector_IdAndQueueNameAndMessageId(
        Long connectorId, String queueName, String messageId);

    Optional<RabbitMqMessageReceipt> findFirstByEvent_IdOrderByProcessedAtDescIdDesc(Long eventId);
}
