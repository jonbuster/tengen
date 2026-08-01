package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * API key for event ingestion. Only the SHA-256 hash of the raw key is stored;
 * the raw value (e.g. {@code tg_abc123}) is shown exactly once at creation.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 32)
    private String prefix;

    /** Null = allowed to ingest any event type. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_event_types", columnDefinition = "jsonb")
    private List<String> allowedEventTypes;

    /** Null = allowed to ingest from any source. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_sources", columnDefinition = "jsonb")
    private List<String> allowedSources;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
