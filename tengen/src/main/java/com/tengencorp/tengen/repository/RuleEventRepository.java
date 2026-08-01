package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.RuleEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RuleEventRepository extends JpaRepository<RuleEvent, Long> {

    @Query("""
        select count(re) from RuleEvent re
        where re.rule.id = :ruleId and re.occurredAt >= :since
        """)
    long countInWindow(@Param("ruleId") Long ruleId, @Param("since") Instant since);

    @Query("""
        select coalesce(sum(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId and re.occurredAt >= :since
        """)
    double sumInWindow(@Param("ruleId") Long ruleId, @Param("since") Instant since);

    @Query("""
        select coalesce(avg(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId and re.occurredAt >= :since
        """)
    double avgInWindow(@Param("ruleId") Long ruleId, @Param("since") Instant since);

    @Query("""
        select coalesce(min(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId and re.occurredAt >= :since
        """)
    double minInWindow(@Param("ruleId") Long ruleId, @Param("since") Instant since);

    @Query("""
        select coalesce(max(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId and re.occurredAt >= :since
        """)
    double maxInWindow(@Param("ruleId") Long ruleId, @Param("since") Instant since);
}
