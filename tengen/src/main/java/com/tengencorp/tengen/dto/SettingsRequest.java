package com.tengencorp.tengen.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SettingsRequest(
    @NotBlank(message = "themeMode is required")
    @Pattern(regexp = "light|dark|system", message = "themeMode must be light, dark, or system")
    String themeMode,

    @NotBlank(message = "accentColor is required")
    @Pattern(regexp = "blue|indigo|purple|teal|green|orange|yellow|red|pink|grey|black|neon",
        message = "accentColor is not supported")
    String accentColor,

    @NotBlank(message = "timeDisplay is required")
    @Pattern(regexp = "local|utc", message = "timeDisplay must be local or utc")
    String timeDisplay) {
}
