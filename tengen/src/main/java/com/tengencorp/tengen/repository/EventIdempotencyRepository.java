package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.EventIdempotency;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EventIdempotencyRepository extends JpaRepository<EventIdempotency, Long> {

    @Modifying
    @Query(value = """
        insert into event_idempotency
            (api_key_id, idempotency_key, request_hash, status, created_at)
        values (:apiKeyId, :idempotencyKey, :requestHash, :status, :createdAt)
        on conflict (api_key_id, idempotency_key) do nothing
        """, nativeQuery = true)
    int insertIfAbsent(@Param("apiKeyId") Long apiKeyId,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("requestHash") String requestHash,
                       @Param("status") String status,
                       @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EventIdempotency> findByApiKeyIdAndIdempotencyKey(Long apiKeyId, String idempotencyKey);
}
