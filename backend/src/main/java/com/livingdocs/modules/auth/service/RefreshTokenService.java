package com.livingdocs.modules.auth.service;

import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.common.security.JwtService;
import com.livingdocs.modules.auth.model.RefreshToken;
import com.livingdocs.modules.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues and rotates long-lived refresh tokens. The raw token is never
 * persisted: only its SHA-256 hash is stored, so a database leak does
 * not directly compromise active sessions.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final JwtService jwtService;
    private final long expirationDays;

    public RefreshTokenService(RefreshTokenRepository repository,
                               JwtService jwtService,
                               @Value("${app.security.refresh-token.expiration-days:30}") long expirationDays) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.expirationDays = expirationDays;
    }

    public record Issued(String rawToken, OffsetDateTime expiresAt) {}

    /**
     * Mint a new raw refresh token and persist its hash.
     */
    @Transactional
    public Issued issue(UUID userId, String userAgent, String ipAddress) {
        byte[] random = new byte[48];
        RNG.nextBytes(random);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash = sha256(raw);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(expirationDays);
        RefreshToken row = new RefreshToken(userId, hash, expiresAt);
        if (userAgent != null) row.setUserAgent(userAgent.length() > 256 ? userAgent.substring(0, 256) : userAgent);
        if (ipAddress != null) row.setIpAddress(ipAddress.length() > 64 ? ipAddress.substring(0, 64) : ipAddress);
        repository.save(row);
        return new Issued(raw, expiresAt);
    }

    /**
     * Validate the supplied raw refresh token, revoke it, and return a
     * brand-new pair (access + refresh). Throws {@link UnauthorizedException}
     * if the token is unknown, expired, or already revoked.
     */
    @Transactional
    public Rotated rotate(String rawToken, String userAgent, String ipAddress) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("Missing refresh token");
        }
        String hash = sha256(rawToken);
        RefreshToken row = repository.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        // Token is single-use: revoke first, then issue a new one.
        repository.delete(row);
        if (row.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new UnauthorizedException("Refresh token expired");
        }
        // Mint a new refresh for the same user
        Issued replacement = issue(row.getUserId(), userAgent, ipAddress);
        return new Rotated(row.getUserId(), replacement.rawToken(), replacement.expiresAt());
    }

    /**
     * Revoke a single refresh token (used by /auth/logout). Silent no-op
     * if the token does not exist.
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repository.findByTokenHash(sha256(rawToken)).ifPresent(repository::delete);
    }

    /**
     * Revoke every refresh token for a user (used by /auth/logout-all).
     */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return repository.deleteAllByUserId(userId);
    }

    /**
     * Issue a new access JWT for a user. Thin wrapper around JwtService
     * so callers don't have to know about role resolution.
     */
    public JwtService.IssuedToken issueAccessToken(UUID userId, String email, List<String> roles) {
        return jwtService.issue(userId, email, roles);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Rotated(UUID userId, String newRawToken, OffsetDateTime newExpiresAt) {}
}
