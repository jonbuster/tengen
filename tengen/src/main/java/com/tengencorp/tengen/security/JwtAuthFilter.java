package com.tengencorp.tengen.security;
import com.tengencorp.tengen.service.JwtService;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Parses {@code Authorization: Bearer <jwt>}, validates it via
 * {@link JwtService} and populates the {@code SecurityContext} with the admin
 * principal. Invalid or missing tokens simply leave the context empty so the
 * authorization rules decide the outcome.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath == null || contextPath.isEmpty()
            ? requestUri : requestUri.substring(contextPath.length());
        return "/api/events".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                String username = jwtService.validateAccess(token).subject();
                var authentication = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // Invalid token — leave the context empty; authorization rules apply.
                if (warningLogRateLimiter.tryAcquire("jwt_invalid", LogSafe.requestPath(request))) {
                    log.warn(
                        "event=security_event name=jwt_invalid method={} path={} reason=invalid_or_expired",
                        request.getMethod(), LogSafe.requestPath(request));
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
