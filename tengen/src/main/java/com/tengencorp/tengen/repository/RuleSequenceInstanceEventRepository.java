package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RuleSequenceInstanceEvent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleSequenceInstanceEventRepository
        extends JpaRepository<RuleSequenceInstanceEvent, Long> {

    @EntityGraph(attributePaths = "event")
    List<RuleSequenceInstanceEvent> findByInstanceIdOrderByStepPositionAsc(Long instanceId);
}
