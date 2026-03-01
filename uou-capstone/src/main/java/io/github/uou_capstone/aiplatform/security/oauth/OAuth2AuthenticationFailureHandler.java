package io.github.uou_capstone.aiplatform.security.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * OAuth2 로그인 실패 핸들러
 * 카카오 로그인 실패 시 프론트엔드로 에러 메시지와 함께 리다이렉트합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Value("${oauth2.redirect.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Value("${oauth2.redirect.path:/auth/callback}")
    private String redirectPath;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException, ServletException {
        log.error("카카오 로그인 실패: {}", exception.getMessage(), exception);

        String errorMessage = "카카오 로그인에 실패했습니다.";
        if (exception.getMessage() != null) {
            errorMessage = exception.getMessage();
        }

        // 프론트엔드로 리다이렉트 (에러 메시지 포함)
        String redirectUrl = UriComponentsBuilder.fromUriString(frontendUrl + redirectPath)
                .queryParam("success", "false")
                .queryParam("error", errorMessage)
                .build()
                .toUriString();

        log.debug("카카오 로그인 실패 리다이렉트: {}", redirectUrl);
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
