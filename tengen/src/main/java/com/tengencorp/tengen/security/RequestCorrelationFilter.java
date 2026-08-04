package com.tengencorp.tengen.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Adds a safe request correlation ID to HTTP logs and responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-ID";
    public static final String MDC_KEY = "requestId";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
            if (previousContext != null) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    static String resolveRequestId(String header) {
        return header != null && VALID_REQUEST_ID.matcher(header).matches()
            ? header : UUID.randomUUID().toString();
    }
}
