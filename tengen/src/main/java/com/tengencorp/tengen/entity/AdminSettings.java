package com.tengencorp.tengen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The single persisted settings row for the admin console. */
@Entity
@Table(name = "admin_settings")
@Getter
@Setter
@NoArgsConstructor
public class AdminSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "theme_mode", nullable = false, length = 10)
    private String themeMode;

    @Column(name = "accent_color", nullable = false, length = 10)
    private String accentColor;

    @Column(name = "time_display", nullable = false, length = 10)
    private String timeDisplay;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AdminSettings(String themeMode, String accentColor, String timeDisplay) {
        this.id = SINGLETON_ID;
        this.themeMode = themeMode;
        this.accentColor = accentColor;
        this.timeDisplay = timeDisplay;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = SINGLETON_ID;
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
