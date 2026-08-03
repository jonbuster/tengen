package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.EventRuleOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRuleOutcomeRepository extends JpaRepository<EventRuleOutcome, Long> {

    List<EventRuleOutcome> findByEventIdOrderByIdAsc(Long eventId);
}
