package io.github.uou_capstone.aiplatform.domain.user.service;

import io.github.uou_capstone.aiplatform.domain.user.dto.RefreshTokenRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.TokenResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.security.jwt.JwtTokenProvider;
import io.github.uou_capstone.aiplatform.security.jwt.RefreshTokenStore;
import io.github.uou_capstone.aiplatform.security.oauth.OAuthExchangeStore;
import io.github.uou_capstone.aiplatform.service.TokenBlacklistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Long USER_ID = 10L;
    private static final String EMAIL = "student@test.com";
    private static final String OLD_REFRESH = "old-refresh-token";
    private static final String OLD_JTI = "old-jti";

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private TeacherRepository teacherRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private OAuthExchangeStore oauthExchangeStore;

    @InjectMocks
    private AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .email(EMAIL)
                .password("encoded")
                .fullName("Student")
                .role(Role.STUDENT)
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void refreshToken_returnsRememberedTokensForDuplicateRotationRequest() {
        when(jwtTokenProvider.getJtiFromToken(OLD_REFRESH)).thenReturn(OLD_JTI);
        when(jwtTokenProvider.getEmailFromToken(OLD_REFRESH)).thenReturn(EMAIL);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(false);
        when(refreshTokenStore.findRememberedRotation(USER_ID, OLD_JTI))
                .thenReturn(Optional.of(new String[]{"new-access", "new-refresh"}));

        TokenResponseDto response = authService.refreshToken(new RefreshTokenRequestDto(OLD_REFRESH));

        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenStore, never()).revokeAllForUser(anyLong());
    }

    @Test
    void refreshToken_remembersRotationAfterConsumingOldJti() {
        when(jwtTokenProvider.getJtiFromToken(OLD_REFRESH)).thenReturn(OLD_JTI);
        when(jwtTokenProvider.getEmailFromToken(OLD_REFRESH)).thenReturn(EMAIL);
        when(jwtTokenProvider.createAccessToken(EMAIL, "STUDENT")).thenReturn("new-access");
        when(jwtTokenProvider.createRefreshToken(eq(EMAIL), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("new-refresh");
        when(jwtTokenProvider.getRefreshTokenExpirationTime()).thenReturn(1_000L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(refreshTokenStore.consume(USER_ID, OLD_JTI)).thenReturn(true);

        TokenResponseDto response = authService.refreshToken(new RefreshTokenRequestDto(OLD_REFRESH));

        assertThat(response.getAccessToken()).isEqualTo("new-access");
        assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenStore).rememberRotation(USER_ID, OLD_JTI, "new-access", "new-refresh", 10_000L);
    }
}
