package io.github.uou_capstone.aiplatform.security.oauth;

import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.security.jwt.JwtTokenProvider;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * OAuth2 로그인 성공 핸들러
 * 카카오 로그인 성공 시 JWT 토큰을 생성하고 프론트엔드로 리다이렉트합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Value("${oauth2.redirect.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${oauth2.redirect.path:/auth/callback}")
    private String redirectPath;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        try {
            // 1. OAuth2User에서 사용자 정보 추출
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            Map<String, Object> attributes = oAuth2User.getAttributes();

            // 2. 카카오 계정 정보 파싱
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            String email = (String) kakaoAccount.get("email");

            if (email == null) {
                log.error("카카오 로그인: 이메일 정보를 가져올 수 없습니다.");
                handleFailure(request, response, "이메일 정보를 가져올 수 없습니다.");
                return;
            }

            // 3. DB에서 사용자 조회
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        log.error("카카오 로그인: 사용자를 찾을 수 없습니다. email={}", email);
                        return new RuntimeException("사용자를 찾을 수 없습니다.");
                    });

            // 4. JWT 토큰 생성
            String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole().name());
            String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail());

            log.info("카카오 로그인 성공: email={}, role={}", user.getEmail(), user.getRole());

            // 5. 프론트엔드로 리다이렉트 (토큰을 URL 파라미터로 전달)
            String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + redirectPath)
                    .queryParam("accessToken", accessToken)
                    .queryParam("refreshToken", refreshToken)
                    .queryParam("success", "true")
                    .build()
                    .toUriString();

            log.debug("카카오 로그인 리다이렉트: {}", redirectUrl);
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);

        } catch (Exception e) {
            log.error("카카오 로그인 처리 중 오류 발생", e);
            handleFailure(request, response, "로그인 처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    /**
     * 로그인 실패 처리
     */
    private void handleFailure(HttpServletRequest request, HttpServletResponse response, String errorMessage) throws IOException {
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + redirectPath)
                .queryParam("success", "false")
                .queryParam("error", errorMessage)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}