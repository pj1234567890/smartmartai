package com.smartmart.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import io.jsonwebtoken.ExpiredJwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    @Test
    void generate_and_extract_token() {
        String secret = "01234567890123456789012345678901"; // 32 chars
        JwtUtil util = new JwtUtil(secret, 10_000);

        String token = util.generateToken(42L, "me@example.com", "SHOPPER");

        assertThat(util.extractEmail(token)).isEqualTo("me@example.com");
        assertThat(util.extractUserId(token)).isEqualTo(42L);
        assertThat(util.extractRole(token)).isEqualTo("SHOPPER");
        assertThat(util.isTokenValid(token, "me@example.com")).isTrue();
    }

    @Test
    void expired_token_is_not_valid() {
        String secret = "01234567890123456789012345678901";
        JwtUtil util = new JwtUtil(secret, -1000);

        String token = util.generateToken(1L, "a@b.c", "SHOPPER");

        // parser will throw for expired tokens
        assertThrows(ExpiredJwtException.class, () -> util.isTokenValid(token, "a@b.c"));
    }
}
