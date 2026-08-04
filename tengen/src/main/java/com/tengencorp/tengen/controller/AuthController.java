package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.AuthResponse;
import com.tengencorp.tengen.dto.LoginRequest;
import com.tengencorp.tengen.dto.RefreshRequest;
import com.tengencorp.tengen.security.AdminUser;
import com.tengencorp.tengen.service.JwtService;
import com.tengencorp.tengen.service.AuthSessionService;
import com.tengencorp.tengen.service.LoginThrottleService;
import com.tengencorp.tengen.helper.LogSafe;
import com.tengencorp.tengen.helper.WarningLogRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues JWT token pairs. {@code POST /api/auth/login} exchanges the env-driven
 * admin credentials for access + refresh tokens; {@code POST /api/auth/refresh}
 * rotates the pair from a valid refresh token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AdminUser adminUser;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final LoginThrottleService loginThrottleService;
    private final WarningLogRateLimiter warningLogRateLimiter = new WarningLogRateLimiter();

    public AuthController(AdminUser adminUser, PasswordEncoder passwordEncoder,
                          AuthSessionService authSessionService,
                          LoginThrottleService loginThrottleService) {
        this.adminUser = adminUser;
        this.passwordEncoder = passwordEncoder;
        this.authSessionService = authSessionService;
        this.loginThrottleService = loginThrottleService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest servletRequest) {
        String remoteAddress = servletRequest.getRemoteAddr();
        try {
            loginThrottleService.check(remoteAddress, request.username());
        } catch (RuntimeException exception) {
            if (exception instanceof com.tengencorp.tengen.exception.TooManyRequestsException) {
                warn("admin_login_throttled", remoteAddress + ':' + request.username(),
                    "event=security_event name=admin_login_throttled actor={} remoteAddress={} reason=rate_limited",
                    LogSafe.text(request.username()), LogSafe.remoteAddress(servletRequest));
            }
            throw exception;
        }
        if (!adminUser.matches(request.username(), request.password(), passwordEncoder)) {
            loginThrottleService.failure(remoteAddress, request.username());
            warn("admin_login_failed", remoteAddress + ':' + request.username(),
                "event=security_event name=admin_login_failed actor={} remoteAddress={} reason=invalid_credentials",
                LogSafe.text(request.username()), LogSafe.remoteAddress(servletRequest));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        loginThrottleService.success(remoteAddress, request.username());
        log.info("event=security_event name=admin_login_succeeded actor={} remoteAddress={} reason=authenticated",
            LogSafe.text(adminUser.getUsername()), LogSafe.remoteAddress(servletRequest));
        return ResponseEntity.ok(authSessionService.create(adminUser.getUsername()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                HttpServletRequest servletRequest) {
        try {
            AuthResponse response = authSessionService.rotate(
                request.refreshToken(), adminUser.getUsername());
            log.info("event=security_event name=admin_refresh_succeeded actor={} remoteAddress={} reason=rotated",
                LogSafe.text(adminUser.getUsername()), LogSafe.remoteAddress(servletRequest));
            return ResponseEntity.ok(response);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException exception) {
            String reason = containsReplay(exception) ? "refresh_replay" : "refresh_rejected";
            warn("admin_refresh_rejected", reason,
                "event=security_event name=admin_refresh_rejected actor={} remoteAddress={} reason={}",
                LogSafe.text(adminUser.getUsername()), LogSafe.remoteAddress(servletRequest), reason);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception exception) {
            log.error("event=security_event name=admin_refresh_failure actor={} remoteAddress={} exceptionType={}",
                LogSafe.text(adminUser.getUsername()), LogSafe.remoteAddress(servletRequest),
                LogSafe.exceptionType(exception), exception);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authSessionService.revoke(request.refreshToken());
        log.info("event=security_event name=admin_logout_succeeded actor=unknown reason=accepted");
        return ResponseEntity.noContent().build();
    }

    private void warn(String category, String stableKey, String message, Object... arguments) {
        if (warningLogRateLimiter.tryAcquire(category, stableKey)) {
            log.warn(message, arguments);
        }
    }

    private boolean containsReplay(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().toLowerCase().contains("replay")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
