package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJobRuleEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayJobRuleEventRepository extends JpaRepository<ReplayJobRuleEvent, Long> {
}
