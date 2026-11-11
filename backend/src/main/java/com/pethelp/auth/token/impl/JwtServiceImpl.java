package com.pethelp.auth.token.impl;

import com.pethelp.auth.token.JwtClaims;
import com.pethelp.auth.token.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtServiceImpl implements JwtService {

    private final SecretKey key;
    private final Duration expiration;

    public JwtServiceImpl(@Value("${jwt.secret}") String secret,
                          @Value("${jwt.expiration}") Duration expiration) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    @Override
    public String generateToken(Long userId, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    @Override
    public JwtClaims parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        return new JwtClaims(userId, roles);
    }
}
