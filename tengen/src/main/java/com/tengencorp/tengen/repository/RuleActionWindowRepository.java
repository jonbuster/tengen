package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleActionWindow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RuleActionWindowRepository extends JpaRepository<RuleActionWindow, Long> {

    @Modifying
    @Query(value = """
        insert into rule_action_windows (rule_id, rule_revision, scope_key, window_start)
        values (:ruleId, :ruleRevision, :scopeKey, :windowStart)
        on conflict do nothing
        """, nativeQuery = true)
    void ensureExists(@Param("ruleId") Long ruleId,
                      @Param("ruleRevision") int ruleRevision,
                      @Param("scopeKey") String scopeKey,
                      @Param("windowStart") Instant windowStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select state from RuleActionWindow state
        where state.rule.id = :ruleId
          and coalesce(state.ruleRevision, 1) = :ruleRevision
          and state.scopeKey = :scopeKey
          and state.windowStart = :windowStart
        """)
    Optional<RuleActionWindow> findForUpdate(@Param("ruleId") Long ruleId,
                                             @Param("ruleRevision") int ruleRevision,
                                             @Param("scopeKey") String scopeKey,
                                             @Param("windowStart") Instant windowStart);

    @Modifying
    @Query("delete from RuleActionWindow state where state.rule.id = :ruleId")
    void deleteByRuleId(@Param("ruleId") Long ruleId);
}
