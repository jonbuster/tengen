package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJob;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplayJobRepository extends JpaRepository<ReplayJob, Long> {

    long countByStatus(ReplayJobStatus status);
}
