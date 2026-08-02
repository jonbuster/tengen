package com.tengencorp.tengen.service;

import com.tengencorp.tengen.dto.AuthResponse;
import com.tengencorp.tengen.entity.RefreshSession;
import com.tengencorp.tengen.repository.RefreshSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Creates, rotates, revokes, and replay-protects refresh sessions. */
@Service
public class AuthSessionService {

    private final JwtService jwtService;
    private final RefreshSessionRepository repository;
    private final long replayGraceSeconds;

    public AuthSessionService(JwtService jwtService, RefreshSessionRepository repository,
                              @Value("${tengen.auth.refresh-replay-grace-seconds:2}")
                              long replayGraceSeconds) {
        this.jwtService = jwtService;
        this.repository = repository;
        this.replayGraceSeconds = replayGraceSeconds;
    }

    @Transactional
    public AuthResponse create(String username) {
        return createPair(username);
    }

    @Transactional
    public AuthResponse rotate(String refreshToken, String expectedUsername) {
        JwtService.TokenClaims claims = jwtService.validateRefresh(refreshToken);
        if (!expectedUsername.equals(claims.subject())) {
            throw new IllegalArgumentException("Refresh session is invalid");
        }
        RefreshSession session = repository.findByTokenId(claims.tokenId())
            .orElseThrow(() -> new IllegalArgumentException("Refresh session is invalid"));
        Instant now = Instant.now();
        if (!MessageDigest.isEqual(
                session.getTokenHash().getBytes(StandardCharsets.US_ASCII),
                hash(refreshToken).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Refresh session is invalid");
        }
        if (!session.isActive(now)) {
            if (session.getUsedAt() != null
                && session.getUsedAt().plusSeconds(replayGraceSeconds).isAfter(now)) {
                throw new IllegalArgumentException("Refresh token was already rotated");
            }
            revokeReplacementChain(session, now);
            throw new IllegalArgumentException("Refresh token replayed or expired");
        }

        AuthResponse replacement = createPair(expectedUsername);
        JwtService.TokenClaims replacementClaims = jwtService.validateRefresh(replacement.refreshToken());
        session.setUsedAt(now);
        session.setRevokedAt(now);
        session.setReplacedByTokenId(replacementClaims.tokenId());
        return replacement;
    }

    @Transactional
    public void revoke(String refreshToken) {
        try {
            JwtService.TokenClaims claims = jwtService.validateRefresh(refreshToken);
            repository.findByTokenId(claims.tokenId()).ifPresent(session -> {
                if (session.getRevokedAt() == null) {
                    session.setRevokedAt(Instant.now());
                }
            });
        } catch (Exception ignored) {
            // Logout is intentionally idempotent and does not reveal token state.
        }
    }

    private AuthResponse createPair(String username) {
        String tokenId = jwtService.newTokenId();
        String refreshToken = jwtService.issueRefreshToken(username, tokenId);
        JwtService.TokenClaims claims = jwtService.validateRefresh(refreshToken);
        repository.save(new RefreshSession(tokenId, hash(refreshToken), username, claims.expiresAt()));
        return new AuthResponse(jwtService.issueAccessToken(username), refreshToken);
    }

    private void revokeReplacementChain(RefreshSession session, Instant now) {
        String replacementId = session.getReplacedByTokenId();
        int depth = 0;
        while (replacementId != null && depth++ < 100) {
            RefreshSession replacement = repository.findByTokenId(replacementId).orElse(null);
            if (replacement == null) {
                return;
            }
            if (replacement.getRevokedAt() == null) {
                replacement.setRevokedAt(now);
            }
            replacementId = replacement.getReplacedByTokenId();
        }
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
