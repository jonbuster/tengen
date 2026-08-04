package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.ReplayJob;
import com.tengencorp.tengen.entity.ReplayJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReplayJobRepository extends JpaRepository<ReplayJob, Long>, JpaSpecificationExecutor<ReplayJob> {

    long countByStatus(ReplayJobStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ReplayJob job where job.id = :id")
    java.util.Optional<ReplayJob> findByIdForUpdate(@Param("id") Long id);
}
