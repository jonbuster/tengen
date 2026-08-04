package com.tengencorp.tengen.repository;

import com.tengencorp.tengen.entity.AdminSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminSettingsRepository extends JpaRepository<AdminSettings, Long> {
}
