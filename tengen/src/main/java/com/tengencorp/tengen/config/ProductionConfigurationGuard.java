package com.tengencorp.tengen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** Fails closed when a production profile uses development credentials. */
@Component
public class ProductionConfigurationGuard implements ApplicationRunner {

    private final Environment environment;
    private final String jwtSecret;
    private final String adminPassword;
    private final String webhookSecret;

    public ProductionConfigurationGuard(
            Environment environment,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${admin.password}") String adminPassword,
            @Value("${tengen.webhook.worker.signing-secret}") String webhookSecret) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.adminPassword = adminPassword;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                || profile.equalsIgnoreCase("production"));
        if (!production) {
            return;
        }
        require(!"admin".equals(adminPassword), "ADMIN_PASSWORD must be changed in production");
        require(jwtSecret.length() >= 32
                && !jwtSecret.startsWith("dev-secret-change-me"),
            "JWT_SECRET must be a unique secret of at least 32 characters in production");
        require(webhookSecret.length() >= 32
                && !webhookSecret.startsWith("dev-webhook-signing-secret"),
            "WEBHOOK_SIGNING_SECRET must be a unique secret of at least 32 characters in production");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
