package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJobTransition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReplayJobTransitionRepository extends JpaRepository<ReplayJobTransition, Long> {

    List<ReplayJobTransition> findByJobIdOrderBySequenceAsc(Long jobId);

    @Query("select coalesce(max(transition.sequence), 0) from ReplayJobTransition transition "
        + "where transition.jobId = :jobId")
    long maxSequence(@Param("jobId") Long jobId);
}
