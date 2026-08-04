package com.tengencorp.tengen.controller;

import com.tengencorp.tengen.dto.SettingsRequest;
import com.tengencorp.tengen.dto.SettingsResponse;
import com.tengencorp.tengen.service.AdminSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin console settings, protected by JWT through the security configuration. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AdminSettingsService settingsService;

    public SettingsController(AdminSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsResponse get() {
        return settingsService.get();
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        return settingsService.update(request);
    }
}
