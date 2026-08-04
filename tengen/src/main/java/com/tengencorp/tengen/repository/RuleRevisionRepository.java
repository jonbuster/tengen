package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleRevision;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RuleRevisionRepository extends JpaRepository<RuleRevision, Long> {

    Page<RuleRevision> findByRuleId(Long ruleId, Pageable pageable);

    Optional<RuleRevision> findByRuleIdAndRevision(Long ruleId, Integer revision);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
        select revision from RuleRevision revision
        where revision.ruleId = :ruleId and revision.revision = :revision
        """)
    Optional<RuleRevision> findForReplay(@Param("ruleId") Long ruleId,
                                         @Param("revision") Integer revision);
}
