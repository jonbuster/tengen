package com.tengencorp.tengen.repository;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    List<Rule> findByActiveTrueOrderByNameAsc();

    List<Rule> findByActiveTrueAndArchivedAtIsNullOrderByNameAsc();

    List<Rule> findByActiveTrueAndArchivedAtIsNullAndEventTypeAndSourceOrderByNameAsc(
        String eventType, String source);

    @Query("""
        select distinct rule from Rule rule
        left join rule.sequenceSteps step
        where rule.active = true and rule.archivedAt is null
          and ((rule.ruleType <> :sequenceType and rule.ruleType <> :absenceType
                and rule.eventType = :eventType and rule.source = :source)
            or (rule.ruleType = :sequenceType
                and step.eventType = :eventType and step.source = :source)
            or (rule.ruleType = :absenceType
                and ((rule.eventType = :eventType and rule.source = :source)
                  or (rule.expectedEventType = :eventType and rule.expectedSource = :source))))
        order by rule.name asc
        """)
    List<Rule> findActiveRulesForEvent(@Param("eventType") String eventType,
                                       @Param("source") String source,
                                       @Param("sequenceType") RuleType sequenceType,
                                       @Param("absenceType") RuleType absenceType);

    List<Rule> findByArchivedAtIsNullOrderByNameAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rule from Rule rule where rule.id = :id")
    Optional<Rule> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Rule> findByName(String name);

    boolean existsByName(String name);
}
