package com.tengencorp.tengen.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates JWT access/refresh tokens. HMAC secret comes from the
 * {@code JWT_SECRET} env var (fallback dev value provided).
 */
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long accessTtlMillis;
    private final long refreshTtlMillis;

    public JwtService(
            @Value("${jwt.secret:dev-secret-change-me-please-32-bytes-min}") String secret,
            @Value("${jwt.access-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMillis = accessTtlMinutes * 60_000L;
        this.refreshTtlMillis = refreshTtlDays * 86_400_000L;
    }

    public String issueAccessToken(String username) {
        return issue(username, accessTtlMillis, "access");
    }

    public String issueRefreshToken(String username) {
        return issue(username, refreshTtlMillis, "refresh");
    }

    private String issue(String username, long ttlMillis, String type) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(username)
            .claim("type", type)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusMillis(ttlMillis)))
            .signWith(secretKey)
            .compact();
    }

    /**
     * Validate the token and return its subject (username). Throws on invalid
     * signature or expiry.
     */
    public String validate(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        return claims.getSubject();
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }
}
