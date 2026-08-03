package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleSequenceInstance;
import com.tengencorp.tengen.entity.RuleSequenceInstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RuleSequenceInstanceRepository extends JpaRepository<RuleSequenceInstance, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select instance from RuleSequenceInstance instance
        where instance.rule.id = :ruleId
          and instance.ruleRevision = :ruleRevision
          and instance.scopeKey = :scopeKey
          and instance.status = :status
          and instance.nextStepPosition in :stepPositions
          and instance.startedAt > :windowStart
          and (instance.lastOccurredAt < :occurredAt
               or (instance.lastOccurredAt = :occurredAt and instance.lastEventId < :eventId))
        order by instance.startedAt asc, instance.id asc
        """)
    List<RuleSequenceInstance> findOldestEligibleForUpdate(
        @Param("ruleId") Long ruleId,
        @Param("ruleRevision") int ruleRevision,
        @Param("scopeKey") String scopeKey,
        @Param("status") RuleSequenceInstanceStatus status,
        @Param("stepPositions") Collection<Integer> stepPositions,
        @Param("windowStart") Instant windowStart,
        @Param("occurredAt") Instant occurredAt,
        @Param("eventId") Long eventId,
        Pageable pageable);

    @Query("""
        select instance from RuleSequenceInstance instance
        where instance.rule.id = :ruleId
          and instance.ruleRevision = :ruleRevision
          and instance.scopeKey = :scopeKey
          and instance.status = :status
          and instance.nextStepPosition in :stepPositions
          and instance.startedAt > :windowStart
          and (instance.lastOccurredAt < :occurredAt
               or (instance.lastOccurredAt = :occurredAt and instance.lastEventId < :eventId))
        order by instance.startedAt asc, instance.id asc
        """)
    List<RuleSequenceInstance> findOldestEligible(
        @Param("ruleId") Long ruleId,
        @Param("ruleRevision") int ruleRevision,
        @Param("scopeKey") String scopeKey,
        @Param("status") RuleSequenceInstanceStatus status,
        @Param("stepPositions") Collection<Integer> stepPositions,
        @Param("windowStart") Instant windowStart,
        @Param("occurredAt") Instant occurredAt,
        @Param("eventId") Long eventId,
        Pageable pageable);

    @Modifying
    @Query("""
        update RuleSequenceInstance instance
        set instance.status = :cancelled, instance.updatedAt = :updatedAt
        where instance.rule.id = :ruleId and instance.status = :active
        """)
    int cancelActiveByRuleId(@Param("ruleId") Long ruleId,
                             @Param("active") RuleSequenceInstanceStatus active,
                             @Param("cancelled") RuleSequenceInstanceStatus cancelled,
                             @Param("updatedAt") Instant updatedAt);
}
