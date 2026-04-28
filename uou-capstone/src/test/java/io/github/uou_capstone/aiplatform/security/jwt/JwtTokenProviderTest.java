package io.github.uou_capstone.aiplatform.security.jwt;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "test-secret-key-for-jwt-token-provider-unit-test-0123456789".getBytes()
    );
    private static final long ACCESS_EXP_MS = 60_000L;
    private static final long REFRESH_EXP_MS = 600_000L;

    private JwtTokenProvider provider;
    private JwtTokenProvider expiredProvider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, ACCESS_EXP_MS, REFRESH_EXP_MS);
        expiredProvider = new JwtTokenProvider(SECRET, -1_000L, -1_000L);
    }

    @Test
    void validateToken_passesForFreshToken() {
        String token = provider.createAccessToken("user@test.com", "STUDENT");
        provider.validateToken(token);
    }

    @Test
    void validateToken_throwsExpiredJwtExceptionForExpiredToken() {
        String expired = expiredProvider.createAccessToken("user@test.com", "STUDENT");
        assertThatThrownBy(() -> provider.validateToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void validateToken_throwsForTamperedSignature() {
        String token = provider.createAccessToken("user@test.com", "STUDENT");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> provider.validateToken(tampered))
                .isInstanceOfAny(SecurityException.class, MalformedJwtException.class);
    }

    @Test
    void validateToken_throwsForEmptyToken() {
        assertThatThrownBy(() -> provider.validateToken(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getAuthentication_returnsAuthenticationForValidToken() {
        String token = provider.createAccessToken("teacher@test.com", "TEACHER");
        Authentication auth = provider.getAuthentication(token);
        assertThat(auth.getName()).isEqualTo("teacher@test.com");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("TEACHER");
    }

    @Test
    void getAuthentication_throwsForExpiredToken() {
        String expired = expiredProvider.createAccessToken("user@test.com", "STUDENT");
        assertThatThrownBy(() -> provider.getAuthentication(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void getEmailFromToken_returnsSubjectForValidToken() {
        String token = provider.createRefreshToken("user@test.com");
        assertThat(provider.getEmailFromToken(token)).isEqualTo("user@test.com");
    }

    @Test
    void getEmailFromToken_throwsForExpiredToken() {
        String expired = expiredProvider.createRefreshToken("user@test.com");
        assertThatThrownBy(() -> provider.getEmailFromToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void getRemainingExpirationTime_positiveForFreshToken() {
        String token = provider.createAccessToken("user@test.com", "STUDENT");
        long remaining = provider.getRemainingExpirationTime(token);
        assertThat(remaining).isGreaterThan(0);
        assertThat(remaining).isLessThanOrEqualTo(ACCESS_EXP_MS);
    }

    @Test
    void getRemainingExpirationTime_zeroForExpiredToken() {
        String expired = expiredProvider.createAccessToken("user@test.com", "STUDENT");
        assertThat(provider.getRemainingExpirationTime(expired)).isEqualTo(0);
    }
}
