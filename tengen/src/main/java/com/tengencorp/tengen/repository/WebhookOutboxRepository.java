package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.WebhookOutbox;
import com.tengencorp.tengen.entity.WebhookOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WebhookOutboxRepository extends JpaRepository<WebhookOutbox, Long> {

    Optional<WebhookOutbox> findByDeduplicationKey(String deduplicationKey);

    @Query("""
        select outbox from WebhookOutbox outbox
        where (:status is null or outbox.status = :status)
          and (:ruleId is null or outbox.ruleId = :ruleId)
          and (:eventId is null or outbox.event.id = :eventId)
          and (:fromTime is null or outbox.createdAt >= :fromTime)
          and (:toTime is null or outbox.createdAt < :toTime)
          and (:search is null
               or lower(outbox.ruleName) like lower(concat('%', :search, '%'))
               or lower(outbox.callbackUrl) like lower(concat('%', :search, '%')))
        """)
    Page<WebhookOutbox> search(
        @Param("status") WebhookOutboxStatus status,
        @Param("ruleId") Long ruleId,
        @Param("eventId") Long eventId,
        @Param("fromTime") Instant fromTime,
        @Param("toTime") Instant toTime,
        @Param("search") String search,
        Pageable pageable);

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
