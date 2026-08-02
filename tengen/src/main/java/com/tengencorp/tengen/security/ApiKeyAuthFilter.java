package com.tengencorp.tengen.security;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.service.ApiKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Reads {@code X-API-Key}, validates the key and populates the
 * {@code SecurityContext} with an {@link ApiKeyPrincipal}. Applied to the
 * event ingestion path ({@code /api/events}).
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/events".equals(request.getServletPath())
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawKey = request.getHeader("X-API-Key");
        if (rawKey == null || rawKey.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is required");
            return;
        }

        try {
            ApiKey key = apiKeyService.findByRawKey(rawKey);
            if (!key.isActive()
                    || (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(Instant.now()))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is invalid or expired");
                return;
            }
            SecurityContextHolder.getContext()
                .setAuthentication(new ApiKeyPrincipal(key.getId(), key.getName()));
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is invalid");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
