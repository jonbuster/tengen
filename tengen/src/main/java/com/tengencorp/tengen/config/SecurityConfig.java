package com.tengencorp.tengen.config;

import com.tengencorp.tengen.security.ApiKeyAuthFilter;
import com.tengencorp.tengen.security.JwtAuthFilter;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security for the admin API and API-key auth for event ingestion.
 * No form login, no Thymeleaf matchers.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthFilter jwtAuthFilter;
    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyAuthFilter apiKeyAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
                .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    {
                        warn("unauthenticated_access", LogSafe.requestPath(request),
                            "event=security_event name=unauthenticated_access method={} path={} principal=anonymous",
                            request.getMethod(), LogSafe.requestPath(request));
                        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized");
                    })
                .accessDeniedHandler((request, response, accessDeniedException) ->
                    {
                        warn("forbidden_access", LogSafe.requestPath(request),
                            "event=security_event name=forbidden_access method={} path={} principal={}",
                            request.getMethod(), LogSafe.requestPath(request), LogSafe.principal(request));
                        response.sendError(HttpStatus.FORBIDDEN.value(), "Forbidden");
                    }))
            .authorizeHttpRequests(auth -> auth
                // Admin APIs use JWT; event ingestion is API-key-only.
                .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").authenticated()
                .requestMatchers("/actuator/prometheus").authenticated()
                .requestMatchers("/api/rules/**", "/api/keys/**", "/api/webhook-deliveries/**",
                    "/api/event-history/**", "/api/replay-jobs/**", "/api/settings",
                    "/api/connectors/rabbitmq/**").authenticated()
                .requestMatchers("/api/events").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class);
        return http.build();
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
