package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.SettingsRequest;
import com.tengencorp.tengen.dto.SettingsResponse;
import com.tengencorp.tengen.entity.AdminSettings;
import com.tengencorp.tengen.repository.AdminSettingsRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSettingsServiceTest {

    @Test
    void returnsDefaultsWhenTheSingletonRowIsMissing() {
        AdminSettingsRepository repository = repository(Optional.empty(), new AtomicReference<>());

        assertThat(new AdminSettingsService(repository).get()).isEqualTo(SettingsResponse.defaults());
    }

    @Test
    void readsThePersistedSingletonRow() {
        AdminSettings settings = new AdminSettings("dark", "teal", "utc");
        AdminSettingsRepository repository = repository(Optional.of(settings), new AtomicReference<>());

        assertThat(new AdminSettingsService(repository).get())
            .isEqualTo(new SettingsResponse("dark", "teal", "utc"));
    }

    @Test
    void updatesAndPersistsAllSettings() {
        AdminSettings settings = new AdminSettings("light", "blue", "local");
        SettingsRequest request = new SettingsRequest("dark", "purple", "utc");
        AtomicReference<AdminSettings> saved = new AtomicReference<>();
        AdminSettingsRepository repository = repository(Optional.of(settings), saved);

        assertThat(new AdminSettingsService(repository).update(request))
            .isEqualTo(new SettingsResponse("dark", "purple", "utc"));
        assertThat(settings.getId()).isEqualTo(AdminSettings.SINGLETON_ID);
        assertThat(settings.getThemeMode()).isEqualTo("dark");
        assertThat(settings.getAccentColor()).isEqualTo("purple");
        assertThat(settings.getTimeDisplay()).isEqualTo("utc");
        assertThat(saved.get()).isSameAs(settings);
    }

    private AdminSettingsRepository repository(Optional<AdminSettings> existing,
                                               AtomicReference<AdminSettings> saved) {
        return (AdminSettingsRepository) Proxy.newProxyInstance(
            AdminSettingsRepository.class.getClassLoader(),
            new Class<?>[] {AdminSettingsRepository.class},
            (ignored, method, arguments) -> switch (method.getName()) {
                case "findById" -> existing;
                case "save" -> {
                    AdminSettings settings = (AdminSettings) arguments[0];
                    saved.set(settings);
                    yield settings;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
