package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJobEvent;
import com.tengencorp.tengen.entity.ReplayJobEventId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayJobEventRepository extends JpaRepository<ReplayJobEvent, ReplayJobEventId> {
}
