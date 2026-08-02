package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.AuthResponse;
import com.tengencorp.tengen.dto.LoginRequest;
import com.tengencorp.tengen.dto.RefreshRequest;
import com.tengencorp.tengen.security.AdminUser;
import com.tengencorp.tengen.service.JwtService;
import com.tengencorp.tengen.service.AuthSessionService;
import com.tengencorp.tengen.service.LoginThrottleService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private final AdminUser adminUser;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService authSessionService;
    private final LoginThrottleService loginThrottleService;

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
        loginThrottleService.check(remoteAddress, request.username());
        if (!adminUser.matches(request.username(), request.password(), passwordEncoder)) {
            loginThrottleService.failure(remoteAddress, request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        loginThrottleService.success(remoteAddress, request.username());
        return ResponseEntity.ok(authSessionService.create(adminUser.getUsername()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            return ResponseEntity.ok(
                authSessionService.rotate(request.refreshToken(), adminUser.getUsername()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authSessionService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
