package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.RuleEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RuleEventRepository extends JpaRepository<RuleEvent, Long> {

    @Query("""
        select count(re) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    long countInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                       @Param("since") Instant since,
                       @Param("until") Instant until, @Param("groupKey") String groupKey);

    @Query("""
        select count(re.value) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    long countValuesInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                             @Param("since") Instant since,
                             @Param("until") Instant until, @Param("groupKey") String groupKey);

    @Query("""
        select coalesce(sum(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    double sumInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                       @Param("since") Instant since,
                       @Param("until") Instant until, @Param("groupKey") String groupKey);

    @Query("""
        select coalesce(avg(re.value), 0.0) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    double avgInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                       @Param("since") Instant since,
                       @Param("until") Instant until, @Param("groupKey") String groupKey);

    @Query("""
        select min(re.value) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    Double minInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                       @Param("since") Instant since,
                       @Param("until") Instant until, @Param("groupKey") String groupKey);

    @Query("""
        select max(re.value) from RuleEvent re
        where re.rule.id = :ruleId
          and coalesce(re.ruleRevision, 1) = :ruleRevision
          and ((:groupKey is null and re.groupKey is null) or re.groupKey = :groupKey)
          and re.occurredAt > :since and re.occurredAt <= :until
        """)
    Double maxInWindow(@Param("ruleId") Long ruleId, @Param("ruleRevision") int ruleRevision,
                       @Param("since") Instant since,
                       @Param("until") Instant until, @Param("groupKey") String groupKey);
}
