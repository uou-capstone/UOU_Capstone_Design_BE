package io.github.uou_capstone.aiplatform.security.oauth;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
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
 * 카카오 OAuth2 로그인 성공 핸들러.
 *
 * 계약(2026-04 확정):
 *  - redirect URL 에 토큰을 절대 포함하지 않는다.
 *  - 성공 시: ?code=<one-time>  (FE 가 POST /api/auth/oauth/exchange 로 토큰 교환)
 *  - 실패 시: ?errorCode=<code>  (메시지 문자열은 노출하지 않음)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final OAuthExchangeStore oauthExchangeStore;

    @Value("${oauth2.redirect.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${oauth2.redirect.path:/auth/callback}")
    private String redirectPath;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        try {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
            Map<String, Object> attributes = oAuth2User.getAttributes();

            @SuppressWarnings("unchecked")
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            String email = kakaoAccount == null ? null : (String) kakaoAccount.get("email");

            if (email == null) {
                log.warn("카카오 로그인: 이메일 정보를 가져올 수 없습니다.");
                redirectWithError(request, response, "oauth_no_email");
                return;
            }

            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        log.warn("카카오 로그인: 사용자를 찾을 수 없습니다. email={}", email);
                        return new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
                    });

            String code = oauthExchangeStore.issue(user.getId());

            String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + redirectPath)
                    .queryParam("code", code)
                    .build()
                    .toUriString();

            log.info("카카오 로그인 성공 → exchange code 발급: userId={}", user.getId());
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);

        } catch (BusinessException e) {
            String errorCode = mapBusinessErrorCode(e);
            redirectWithError(request, response, errorCode);
        } catch (Exception e) {
            log.error("카카오 로그인 처리 중 오류", e);
            redirectWithError(request, response, "oauth_internal_error");
        }
    }

    private void redirectWithError(HttpServletRequest request, HttpServletResponse response, String errorCode) throws IOException {
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + redirectPath)
                .queryParam("errorCode", errorCode)
                .build()
                .toUriString();
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }

    private String mapBusinessErrorCode(BusinessException e) {
        if (e.getErrorCode() == CommonErrorCode.MEMBER_NOT_FOUND) {
            return "oauth_user_not_found";
        }
        return "oauth_internal_error";
    }
}
