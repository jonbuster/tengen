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
import java.util.UUID;

/** Issues and validates type-safe access and refresh JWTs. */
@Service
public class JwtService {

    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";

    private final SecretKey secretKey;
    private final long accessTtlMillis;
    private final long refreshTtlMillis;
    private final String issuer;
    private final String audience;

    public JwtService(
            @Value("${jwt.secret:dev-secret-change-me-please-32-bytes-min}") String secret,
            @Value("${jwt.access-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${jwt.refresh-ttl-days:7}") long refreshTtlDays,
            @Value("${jwt.issuer:tengen}") String issuer,
            @Value("${jwt.audience:tengen-admin}") String audience) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMillis = accessTtlMinutes * 60_000L;
        this.refreshTtlMillis = refreshTtlDays * 86_400_000L;
        this.issuer = issuer;
        this.audience = audience;
    }

    public String issueAccessToken(String username) {
        return issue(username, accessTtlMillis, ACCESS, UUID.randomUUID().toString());
    }

    public String issueRefreshToken(String username, String tokenId) {
        return issue(username, refreshTtlMillis, REFRESH, tokenId);
    }

    public String newTokenId() {
        return UUID.randomUUID().toString();
    }

    private String issue(String username, long ttlMillis, String type, String tokenId) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(issuer)
            .audience().single(audience)
            .subject(username)
            .id(tokenId)
            .claim("type", type)
            .issuedAt(Date.from(now))
            .notBefore(Date.from(now))
            .expiration(Date.from(now.plusMillis(ttlMillis)))
            .signWith(secretKey)
            .compact();
    }

    public TokenClaims validateAccess(String token) {
        return validate(token, ACCESS);
    }

    public TokenClaims validateRefresh(String token) {
        return validate(token, REFRESH);
    }

    private TokenClaims validate(String token, String expectedType) {
        Claims claims = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(issuer)
            .requireAudience(audience)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String type = claims.get("type", String.class);
        if (!expectedType.equals(type)) {
            throw new IllegalArgumentException("Unexpected JWT type");
        }
        if (claims.getSubject() == null || claims.getSubject().isBlank()
            || claims.getId() == null || claims.getId().isBlank()
            || claims.getIssuedAt() == null || claims.getExpiration() == null) {
            throw new IllegalArgumentException("JWT claims are incomplete");
        }
        return new TokenClaims(
            claims.getSubject(),
            claims.getId(),
            claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant());
    }

    public record TokenClaims(String subject, String tokenId, Instant issuedAt, Instant expiresAt) {
    }
}
