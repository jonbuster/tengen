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
        insert into rule_action_windows (rule_id, scope_key, window_start)
        values (:ruleId, :scopeKey, :windowStart)
        on conflict (rule_id, scope_key, window_start) do nothing
        """, nativeQuery = true)
    void ensureExists(@Param("ruleId") Long ruleId,
                      @Param("scopeKey") String scopeKey,
                      @Param("windowStart") Instant windowStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select state from RuleActionWindow state
        where state.rule.id = :ruleId
          and state.scopeKey = :scopeKey
          and state.windowStart = :windowStart
        """)
    Optional<RuleActionWindow> findForUpdate(@Param("ruleId") Long ruleId,
                                             @Param("scopeKey") String scopeKey,
                                             @Param("windowStart") Instant windowStart);
}
