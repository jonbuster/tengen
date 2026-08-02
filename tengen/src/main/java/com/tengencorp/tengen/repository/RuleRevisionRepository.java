package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RuleRevisionRepository extends JpaRepository<RuleRevision, Long> {

    Page<RuleRevision> findByRuleId(Long ruleId, Pageable pageable);

    Optional<RuleRevision> findByRuleIdAndRevision(Long ruleId, Integer revision);
}
