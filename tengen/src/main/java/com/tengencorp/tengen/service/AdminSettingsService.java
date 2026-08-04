package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.SettingsRequest;
import com.tengencorp.tengen.dto.SettingsResponse;
import com.tengencorp.tengen.entity.AdminSettings;
import com.tengencorp.tengen.repository.AdminSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and updates the singleton settings row for the admin console. */
@Service
public class AdminSettingsService {

    private final AdminSettingsRepository repository;

    public AdminSettingsService(AdminSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get() {
        return repository.findById(AdminSettings.SINGLETON_ID)
            .map(SettingsResponse::from)
            .orElseGet(SettingsResponse::defaults);
    }

    @Transactional
    public SettingsResponse update(SettingsRequest request) {
        AdminSettings settings = repository.findById(AdminSettings.SINGLETON_ID)
            .orElseGet(() -> new AdminSettings(
                request.themeMode(), request.accentColor(), request.timeDisplay()));
        settings.setThemeMode(request.themeMode());
        settings.setAccentColor(request.accentColor());
        settings.setTimeDisplay(request.timeDisplay());
        settings.setId(AdminSettings.SINGLETON_ID);
        return SettingsResponse.from(repository.save(settings));
    }
}
