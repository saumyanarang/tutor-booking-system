package com.saumyanarang.tutorbooking.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Component
public class JwtUtil {

    private static final long EXPIRATION_MS = 86_400_000L; // 1 day

    private final Key key;

    /**
     * The signing secret comes from configuration (JWT_SECRET in the environment), not from source code.
     * HS256 needs at least 256 bits, so a shorter secret stops the application at startup instead of
     * failing on the first login.
     */
    public JwtUtil(@Value("${jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret (JWT_SECRET) must be at least 32 bytes long for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * Generates a JWT token with user information
     *
     * @param email User's email
     * @return The generated JWT token
     */
    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    /**
     * Extracts the user email from a JWT token
     *
     * @param token JWT token
     * @return User's email
     */
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Gets the expiration date of a JWT token as LocalDateTime
     *
     * @param token JWT token
     * @return Expiration date as LocalDateTime
     */
    public LocalDateTime getExpirationDate(String token) {
        Date expiration = extractAllClaims(token).getExpiration();
        return expiration.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    /**
     * Extracts all claims from a JWT token
     *
     * @param token JWT token
     * @return All claims in the token
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * Validates if a JWT token is valid
     *
     * @param token JWT token
     * @return true if valid, false otherwise
     */
    public boolean isTokenValid(String token) {
        try {
            extractEmail(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
