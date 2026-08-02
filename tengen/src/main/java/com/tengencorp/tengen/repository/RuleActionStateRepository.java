package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleActionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RuleActionStateRepository extends JpaRepository<RuleActionState, Long> {

    @Modifying
    @Query(value = """
        insert into rule_action_state (rule_id, scope_key, last_matched)
        values (:ruleId, :scopeKey, false)
        on conflict (rule_id, scope_key) do nothing
        """, nativeQuery = true)
    void ensureExists(@Param("ruleId") Long ruleId, @Param("scopeKey") String scopeKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select state from RuleActionState state
        where state.rule.id = :ruleId and state.scopeKey = :scopeKey
        """)
    Optional<RuleActionState> findForUpdate(@Param("ruleId") Long ruleId,
                                            @Param("scopeKey") String scopeKey);
}
