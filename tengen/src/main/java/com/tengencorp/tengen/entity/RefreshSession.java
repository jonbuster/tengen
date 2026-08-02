package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Hashed, one-time-use refresh session. */
@Entity
@Table(name = "refresh_sessions")
@Getter
@Setter
@NoArgsConstructor
public class RefreshSession {

    @Id
    @Column(name = "token_id", length = 36)
    private String tokenId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_token_id", length = 36)
    private String replacedByTokenId;

    public RefreshSession(String tokenId, String tokenHash, String username, Instant expiresAt) {
        this.tokenId = tokenId;
        this.tokenHash = tokenHash;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isActive(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
