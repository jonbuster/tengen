package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.NotificationOutbox;
import com.tengencorp.tengen.entity.NotificationOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    Optional<NotificationOutbox> findByDeduplicationKey(String deduplicationKey);

    List<NotificationOutbox> findByEvent_IdOrderByCreatedAtDescIdDesc(Long eventId);

    long countByStatusIn(Collection<NotificationOutboxStatus> statuses);

    Optional<NotificationOutbox> findFirstByStatusInOrderByCreatedAtAsc(
        Collection<NotificationOutboxStatus> statuses);

    @Query(value = """
        select * from notification_outbox
        where (
            (status in ('PENDING', 'RETRY_SCHEDULED') and next_attempt_at <= :now)
            or (status = 'PROCESSING'
                and (lease_expires_at is null or lease_expires_at <= :now))
        )
        order by next_attempt_at asc, id asc
        limit :batchSize
        for update skip locked
        """, nativeQuery = true)
    List<NotificationOutbox> findClaimable(@Param("now") Instant now,
                                           @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NotificationOutbox> findByIdAndLeaseTokenAndStatus(
        Long id, String leaseToken, NotificationOutboxStatus status);
}
