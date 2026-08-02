package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.RefreshSession;
import com.tengencorp.tengen.repository.RefreshSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthSessionServiceTest {

    @Test
    void replayRevokesTheReplacementSession() {
        Map<String, RefreshSession> sessions = new HashMap<>();
        RefreshSessionRepository repository = (RefreshSessionRepository) Proxy.newProxyInstance(
            RefreshSessionRepository.class.getClassLoader(),
            new Class<?>[] {RefreshSessionRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "save" -> {
                    RefreshSession session = (RefreshSession) args[0];
                    sessions.put(session.getTokenId(), session);
                    yield session;
                }
                case "findByTokenId" -> Optional.ofNullable(sessions.get((String) args[0]));
                case "toString" -> "InMemoryRefreshSessionRepository";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });

        JwtService jwtService = new JwtService(
            "01234567890123456789012345678901", 15, 7, "tengen-test", "tengen-admin-test");
        AuthSessionService service = new AuthSessionService(jwtService, repository, 0);
        var original = service.create("admin");
        var replacement = service.rotate(original.refreshToken(), "admin");
        var newest = service.rotate(replacement.refreshToken(), "admin");

        assertThatThrownBy(() -> service.rotate(original.refreshToken(), "admin"))
            .hasMessageContaining("replayed");
        assertThatThrownBy(() -> service.rotate(newest.refreshToken(), "admin"))
            .hasMessageContaining("replayed");
    }
}
