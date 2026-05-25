package com.byonix.shoplink.security;

import com.byonix.shoplink.domain.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    public static final String CLAIM_PURPOSE = "purpose";
    public static final String PURPOSE_MFA_CHALLENGE = "mfa_challenge";
    public static final String PURPOSE_ACCESS = "access";

    private final SecretKey key;
    private final long accessMinutes;
    private final long mfaChallengeMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-expiration-minutes}") long accessMinutes,
                      @Value("${app.auth.mfa-challenge-expiration-minutes:5}") long mfaChallengeMinutes) {
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessMinutes = accessMinutes;
        this.mfaChallengeMinutes = mfaChallengeMinutes;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_PURPOSE, PURPOSE_ACCESS)
                .claim("uid", user.getId().toString())
                .claim("role", user.getRole().name())
                .claim("tokenVersion", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public String createMfaChallengeToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(CLAIM_PURPOSE, PURPOSE_MFA_CHALLENGE)
                .claim("uid", user.getId().toString())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(mfaChallengeMinutes * 60)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Claims parseMfaChallenge(String token) {
        Claims claims = parse(token);
        if (!PURPOSE_MFA_CHALLENGE.equals(claims.get(CLAIM_PURPOSE, String.class))) {
            throw new JwtException("Invalid MFA challenge token");
        }
        return claims;
    }

    public UUID userId(Claims claims) {
        return UUID.fromString(claims.get("uid", String.class));
    }
}
