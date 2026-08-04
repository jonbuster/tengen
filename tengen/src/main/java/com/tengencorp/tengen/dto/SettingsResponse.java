package com.tengencorp.tengen.dto;

import com.tengencorp.tengen.entity.AdminSettings;

public record SettingsResponse(
    String themeMode,
    String accentColor,
    String timeDisplay) {

    public static SettingsResponse from(AdminSettings settings) {
        return new SettingsResponse(
            settings.getThemeMode(), settings.getAccentColor(), settings.getTimeDisplay());
    }

    public static SettingsResponse defaults() {
        return new SettingsResponse("light", "blue", "local");
    }
}
