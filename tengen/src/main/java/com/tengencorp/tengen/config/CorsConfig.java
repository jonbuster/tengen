package com.tengencorp.tengen.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the Next.js frontend. Dev origin is localhost:3000; production uses
 * the compose network hostname (overridable via CORS_ALLOWED_ORIGINS).
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${cors.allowed-origins:http://localhost:3000}") String[] allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins(allowedOrigins)
                    .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders("Authorization", "Content-Type", "X-API-Key")
                    .exposedHeaders("X-API-Key")
                    .allowCredentials(true)
                    .maxAge(3600);
            }
        };
    }
}
