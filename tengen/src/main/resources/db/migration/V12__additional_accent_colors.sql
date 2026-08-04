ALTER TABLE admin_settings
    DROP CONSTRAINT admin_settings_accent_color_check;

ALTER TABLE admin_settings
    ADD CONSTRAINT admin_settings_accent_color_check
    CHECK (accent_color IN (
        'blue', 'indigo', 'purple', 'teal', 'green', 'orange',
        'yellow', 'red', 'pink', 'grey', 'black', 'neon'
    ));
