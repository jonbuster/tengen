package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long>,
                                                JpaSpecificationExecutor<WebhookOutbox> {

    Optional<WebhookOutbox> findByDeduplicationKey(String deduplicationKey);

    long countByStatusIn(Collection<WebhookOutboxStatus> statuses);

    Optional<WebhookOutbox> findFirstByStatusInOrderByCreatedAtAsc(
        Collection<WebhookOutboxStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from WebhookOutbox outbox where outbox.id = :id")
    Optional<WebhookOutbox> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
        select * from webhook_outbox
        where (
            (status in ('PENDING', 'RETRY_SCHEDULED') and next_attempt_at <= :now)
            or (status = 'PROCESSING'
                and (lease_expires_at is null or lease_expires_at <= :now))
        )
        order by next_attempt_at asc, id asc
        limit :batchSize
        for update skip locked
        """, nativeQuery = true)
    List<WebhookOutbox> findClaimable(@Param("now") Instant now,
                                      @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WebhookOutbox> findByIdAndLeaseTokenAndStatus(Long id, String leaseToken,
                                                            WebhookOutboxStatus status);
}
