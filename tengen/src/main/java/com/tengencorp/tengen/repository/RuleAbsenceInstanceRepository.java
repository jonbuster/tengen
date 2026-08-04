package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleAbsenceInstance;
import com.tengencorp.tengen.entity.RuleAbsenceInstanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RuleAbsenceInstanceRepository extends JpaRepository<RuleAbsenceInstance, Long> {

    @Modifying
    @Query(value = """
        insert into rule_absence_instances
            (rule_id, rule_revision, scope_key, start_event_id, start_occurred_at,
             deadline_at, status, created_at, updated_at)
        values (:ruleId, :ruleRevision, :scopeKey, :startEventId, :startOccurredAt,
                :deadlineAt, 'PENDING', :now, :now)
        on conflict (rule_id, rule_revision, scope_key) where status = 'PENDING' do nothing
        """, nativeQuery = true)
    int insertPending(@Param("ruleId") Long ruleId,
                      @Param("ruleRevision") int ruleRevision,
                      @Param("scopeKey") String scopeKey,
                      @Param("startEventId") Long startEventId,
                      @Param("startOccurredAt") Instant startOccurredAt,
                      @Param("deadlineAt") Instant deadlineAt,
                      @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select instance from RuleAbsenceInstance instance
        where instance.rule.id = :ruleId
          and instance.ruleRevision = :ruleRevision
          and instance.scopeKey = :scopeKey
          and instance.status = :status
        """)
    Optional<RuleAbsenceInstance> findPendingForUpdate(
        @Param("ruleId") Long ruleId,
        @Param("ruleRevision") int ruleRevision,
        @Param("scopeKey") String scopeKey,
        @Param("status") RuleAbsenceInstanceStatus status);

    List<RuleAbsenceInstance> findByStatusAndDeadlineAtLessThanEqualOrderByDeadlineAtAscIdAsc(
        RuleAbsenceInstanceStatus status, Instant deadlineAt, Pageable pageable);

    @Query(value = """
        select * from rule_absence_instances
        where status = :status and deadline_at <= :now
        order by deadline_at asc, id asc
        limit :batchSize
        """, nativeQuery = true)
    List<RuleAbsenceInstance> findDue(
        @Param("status") String status,
        @Param("now") Instant now,
        @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select instance from RuleAbsenceInstance instance where instance.id = :id")
    Optional<RuleAbsenceInstance> findByIdForUpdate(@Param("id") Long id);

    List<RuleAbsenceInstance> findByStartEvent_IdOrderByCreatedAtAscIdAsc(Long eventId);

    List<RuleAbsenceInstance> findByResolvedByEvent_IdOrderByCreatedAtAscIdAsc(Long eventId);

    long countByStatus(RuleAbsenceInstanceStatus status);

    @Modifying
    @Query("""
        update RuleAbsenceInstance instance
        set instance.status = :cancelled,
            instance.resolvedAt = :resolvedAt,
            instance.updatedAt = :resolvedAt
        where instance.rule.id = :ruleId
          and instance.status = :pending
        """)
    int cancelPendingByRuleId(@Param("ruleId") Long ruleId,
                              @Param("pending") RuleAbsenceInstanceStatus pending,
                              @Param("cancelled") RuleAbsenceInstanceStatus cancelled,
                              @Param("resolvedAt") Instant resolvedAt);

    @Query("""
        select instance from RuleAbsenceInstance instance
        where instance.rule.id = :ruleId
          and instance.ruleRevision = :ruleRevision
          and instance.scopeKey = :scopeKey
          and instance.status = :status
          and (instance.startOccurredAt < :occurredAt
            or (instance.startOccurredAt = :occurredAt and instance.startEvent.id < :eventId))
          and :occurredAt <= instance.deadlineAt
        order by instance.startOccurredAt asc, instance.id asc
        """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RuleAbsenceInstance> findSatisfiable(
        @Param("ruleId") Long ruleId,
        @Param("ruleRevision") int ruleRevision,
        @Param("scopeKey") String scopeKey,
        @Param("status") RuleAbsenceInstanceStatus status,
        @Param("occurredAt") Instant occurredAt,
        @Param("eventId") Long eventId,
        Pageable pageable);
}
