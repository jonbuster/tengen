package com.tengencorp.tengen.controller;
import com.tengencorp.tengen.dto.AuthResponse;
import com.tengencorp.tengen.dto.LoginRequest;
import com.tengencorp.tengen.dto.RefreshRequest;
import com.tengencorp.tengen.security.AdminUser;
import com.tengencorp.tengen.service.JwtService;

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

    private final JwtService jwtService;
    private final AdminUser adminUser;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtService jwtService, AdminUser adminUser, PasswordEncoder passwordEncoder) {
        this.jwtService = jwtService;
        this.adminUser = adminUser;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        if (!adminUser.matches(request.username(), request.password(), passwordEncoder)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(tokenPair(adminUser.getUsername()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            String username = jwtService.validate(request.refreshToken());
            if (!adminUser.getUsername().equals(username)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            return ResponseEntity.ok(tokenPair(username));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    private AuthResponse tokenPair(String username) {
        return new AuthResponse(jwtService.issueAccessToken(username), jwtService.issueRefreshToken(username));
    }
}
