package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Reusable provider connection. Secret material is encrypted at rest. */
@Entity
@Table(name = "notification_destinations")
@Getter
@Setter
@NoArgsConstructor
public class NotificationDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "destination_key", nullable = false, unique = true, length = 36)
    private String destinationKey;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(nullable = false, length = 40)
    private String provider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> configuration = new LinkedHashMap<>();

    @Column(name = "credential_ciphertext", columnDefinition = "bytea")
    private byte[] credentialCiphertext;

    @Column(name = "credential_nonce", columnDefinition = "bytea")
    private byte[] credentialNonce;

    @Column(name = "encryption_key_version")
    private Integer encryptionKeyVersion;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_succeeded")
    private Boolean lastTestSucceeded;

    @Column(name = "last_test_error_category", length = 80)
    private String lastTestErrorCategory;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (destinationKey == null || destinationKey.isBlank()) {
            destinationKey = UUID.randomUUID().toString();
        }
        if (configuration == null) {
            configuration = new LinkedHashMap<>();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean hasCredentials() {
        return credentialCiphertext != null && credentialNonce != null
            && encryptionKeyVersion != null;
    }
}
