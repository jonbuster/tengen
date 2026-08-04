package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJobOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplayJobOutcomeRepository extends JpaRepository<ReplayJobOutcome, Long> {

    @Query("""
        select outcome from ReplayJobOutcome outcome
        where outcome.jobId = :jobId
          and (:matched is null or outcome.matched = :matched)
        order by outcome.inputPosition asc
        """)
    Page<ReplayJobOutcome> findPage(@Param("jobId") Long jobId,
                                    @Param("matched") Boolean matched,
                                    Pageable pageable);
}
