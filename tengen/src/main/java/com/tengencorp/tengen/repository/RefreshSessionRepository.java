package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshSession> findByTokenId(String tokenId);

    long deleteByExpiresAtBefore(Instant cutoff);
}
