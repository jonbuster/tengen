CREATE TABLE admin_settings (
    id bigint PRIMARY KEY,
    theme_mode varchar(10) NOT NULL,
    accent_color varchar(10) NOT NULL,
    time_display varchar(10) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT admin_settings_singleton_check CHECK (id = 1),
    CONSTRAINT admin_settings_theme_mode_check
        CHECK (theme_mode IN ('light', 'dark', 'system')),
    CONSTRAINT admin_settings_accent_color_check
        CHECK (accent_color IN ('blue', 'indigo', 'purple', 'teal', 'green', 'orange')),
    CONSTRAINT admin_settings_time_display_check
        CHECK (time_display IN ('local', 'utc'))
);

INSERT INTO admin_settings (id, theme_mode, accent_color, time_display)
VALUES (1, 'light', 'blue', 'local');
