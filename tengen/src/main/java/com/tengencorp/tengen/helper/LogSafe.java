package com.tengencorp.tengen.helper;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

/** Small helpers for keeping operational log fields bounded and single-line. */
public final class LogSafe {

    private static final int MAX_FIELD_LENGTH = 200;

    private LogSafe() {
    }

    public static String text(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), MAX_FIELD_LENGTH));
        for (int index = 0; index < value.length() && sanitized.length() < MAX_FIELD_LENGTH; index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                sanitized.append('_');
            } else if (character == '=') {
                sanitized.append('?');
            } else {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }

    public static String requestPath(HttpServletRequest request) {
        return text(request.getRequestURI());
    }

    public static String remoteAddress(HttpServletRequest request) {
        return text(request.getRemoteAddr());
    }

    public static String principal(HttpServletRequest request) {
        return request.getUserPrincipal() != null
            ? text(request.getUserPrincipal().getName()) : "anonymous";
    }

    public static String actor(Authentication authentication) {
        return authentication != null && authentication.getName() != null
            ? text(authentication.getName()) : "system";
    }

    public static String exceptionType(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
