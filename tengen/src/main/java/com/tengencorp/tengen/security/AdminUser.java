package com.tengencorp.tengen.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * In-memory admin user driven by {@code ADMIN_USER} / {@code ADMIN_PASSWORD}
 * env vars (BCrypt-hashed at startup). Defaults to admin/admin for local dev.
 */
@Component
public class AdminUser {

    private final String username;
    private final String passwordHash;

    public AdminUser(@Value("${admin.user:admin}") String username,
                     @Value("${admin.password:admin}") String password,
                     PasswordEncoder passwordEncoder) {
        this.username = username;
        this.passwordHash = passwordEncoder.encode(password);
    }

    public String getUsername() {
        return username;
    }

    public boolean matches(String username, String rawPassword, PasswordEncoder encoder) {
        return this.username.equals(username) && encoder.matches(rawPassword, passwordHash);
    }
}
