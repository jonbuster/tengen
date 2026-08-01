package com.tengencorp.tengen.security;
import com.tengencorp.tengen.entity.ApiKey;

import com.tengencorp.tengen.entity.Event;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Authenticated principal representing a validated API key. Implements
 * {@link Authentication} so the event controller can read the key id directly
 * from the security context.
 */
public class ApiKeyPrincipal implements Authentication {

    private final Long keyId;
    private final String name;

    public ApiKeyPrincipal(Long keyId, String name) {
        this.keyId = keyId;
        this.name = name;
    }

    public Long getKeyId() {
        return keyId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return this;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        throw new IllegalArgumentException("ApiKeyPrincipal is immutable");
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * Scope check: the key must be active, unexpired and allow this event's
     * type and source (unless unrestricted by null allow-lists).
     */
    public boolean allows(Event event, ApiKey key) {
        if (!key.isActive()) {
            return false;
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            return false;
        }
        if (key.getAllowedEventTypes() != null && !key.getAllowedEventTypes().contains(event.getType())) {
            return false;
        }
        if (key.getAllowedSources() != null && !key.getAllowedSources().contains(event.getSource())) {
            return false;
        }
        return true;
    }
}
