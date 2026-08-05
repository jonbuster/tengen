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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Immutable notification content version. */
@Entity
@Table(name = "notification_templates", uniqueConstraints = @UniqueConstraint(
    name = "uk_notification_templates_name_version",
    columnNames = {"name", "version"}))
@Getter
@Setter
@NoArgsConstructor
public class NotificationTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "subject_template", columnDefinition = "text")
    private String subjectTemplate;

    @Column(name = "text_template", nullable = false, columnDefinition = "text")
    private String textTemplate;

    @Column(name = "html_template", columnDefinition = "text")
    private String htmlTemplate;

    @Column(name = "css_template", columnDefinition = "text")
    private String cssTemplate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
