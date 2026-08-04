package com.tengencorp.tengen.security;
import com.tengencorp.tengen.entity.ApiKey;
import com.tengencorp.tengen.service.ApiKeyService;
import com.tengencorp.tengen.service.ApiKeyRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import com.tengencorp.tengen.exception.NotFoundException;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final ApiKeyService apiKeyService;
    private final ApiKeyRateLimiter rateLimiter;
    private final long maxBodyBytes;
    private final Counter authenticationFailures;
    private final Counter rateLimited;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ApiKeyRateLimiter rateLimiter,
                            MeterRegistry meterRegistry,
                            @Value("${tengen.ingestion.max-body-bytes:1048576}") long maxBodyBytes) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.maxBodyBytes = maxBodyBytes;
        this.authenticationFailures = meterRegistry.counter("tengen.authentication.failures",
            "credential", "api-key");
        this.rateLimited = meterRegistry.counter("tengen.ingestion.rate_limited");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/api/events".equals(requestPath(request))
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty()
            ? requestUri : requestUri.substring(contextPath.length());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawKey = request.getHeader("X-API-Key");
        if (request.getContentLengthLong() > maxBodyBytes) {
            warn("api_key_body_too_large", "global",
                "event=security_event name=api_key_body_too_large method={} path={} contentLength={} limit={}",
                request.getMethod(), LogSafe.requestPath(request), request.getContentLengthLong(), maxBodyBytes);
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "Request body exceeds the configured limit");
            return;
        }
        if (rawKey == null || rawKey.isBlank()) {
            authenticationFailures.increment();
            warn("api_key_missing", "global",
                "event=security_event name=api_key_missing method={} path={} reason=credential_required",
                request.getMethod(), LogSafe.requestPath(request));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is required");
            return;
        }

        try {
            ApiKey key = apiKeyService.findByRawKey(rawKey);
            if (!key.isActive()
                    || (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(Instant.now()))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is invalid or expired");
                authenticationFailures.increment();
                String reason = key.isActive() ? "expired" : "inactive";
                warn("api_key_invalid", String.valueOf(key.getId()),
                    "event=security_event name=api_key_invalid method={} path={} keyId={} reason={}",
                    request.getMethod(), LogSafe.requestPath(request), key.getId(), reason);
                return;
            }
            if (!rateLimiter.tryAcquire(key.getId())) {
                rateLimited.increment();
                warn("api_key_rate_limited", String.valueOf(key.getId()),
                    "event=security_event name=api_key_rate_limited method={} path={} keyId={} retryAfterSeconds=60",
                    request.getMethod(), LogSafe.requestPath(request), key.getId());
                response.setHeader("Retry-After", "60");
                response.sendError(429, "API key rate limit exceeded");
                return;
            }
            SecurityContextHolder.getContext()
                .setAuthentication(new ApiKeyPrincipal(key.getId(), key.getName()));
        } catch (NotFoundException e) {
            authenticationFailures.increment();
            warn("api_key_invalid", "unknown",
                "event=security_event name=api_key_invalid method={} path={} keyId=unknown reason=not_found",
                request.getMethod(), LogSafe.requestPath(request));
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "API key is invalid");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }
}
