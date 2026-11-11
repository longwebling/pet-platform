package com.pethelp.auth;

import com.pethelp.auth.token.JwtClaims;
import com.pethelp.auth.token.JwtService;
import com.pethelp.auth.token.impl.JwtServiceImpl;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void generateToken_roundTripsUserIdAndRoles() {
        JwtService service = new JwtServiceImpl(SECRET, Duration.ofHours(2));

        String token = service.generateToken(42L, List.of("user", "ops"));

        JwtClaims claims = service.parseToken(token);
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.roles()).containsExactly("user", "ops");
    }

    @Test
    void parseToken_invalidToken_throws() {
        JwtService service = new JwtServiceImpl(SECRET, Duration.ofHours(2));

        assertThatThrownBy(() -> service.parseToken("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseToken_expiredToken_throws() {
        JwtService service = new JwtServiceImpl(SECRET, Duration.ofSeconds(-60));

        String token = service.generateToken(1L, List.of("user"));

        assertThatThrownBy(() -> service.parseToken(token))
                .isInstanceOf(JwtException.class);
    }
}
