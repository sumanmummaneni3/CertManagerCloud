package com.codecatalyst.security;

import com.codecatalyst.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and validates JWTs after a successful Google OAuth2 login.
 *
 * Token claims:
 *   sub      — user UUID
 *   email    — user email
 *   orgId    — organisation UUID (tenant scope for every query)
 *   role     — ADMIN or READ_ONLY
 *   iat/exp  — issued-at / expiry
 *
 * The client sends the token on every subsequent request as:
 *   Authorization: Bearer <token>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey  signingKey;
    private final long       expirationMs;

    public JwtService(
            @Value("${certmonitor.jwt.secret}") String secret,
            @Value("${certmonitor.jwt.expiration-ms:86400000}") long expirationMs) {

        if (secret.length() < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 characters — set certmonitor.jwt.secret");
        }
        this.signingKey   = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /** Issue a signed JWT for a successfully authenticated user. */
    public String issue(User user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email",  user.getEmail())
                .claim("orgId",  user.getOrganization().getId().toString())
                .claim("role",   user.getRole().name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and validate a JWT.
     *
     * @return parsed Claims if valid
     * @throws JwtException if the token is invalid, expired, or tampered with
     */
    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Extract claims without throwing — returns null on any error. */
    public Claims tryParse(String token) {
        try {
            return validate(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT parse failed: {}", e.getMessage());
            return null;
        }
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID extractOrgId(Claims claims) {
        return UUID.fromString(claims.get("orgId", String.class));
    }

    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }
}
