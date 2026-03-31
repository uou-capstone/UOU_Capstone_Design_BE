package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate Limiting Interceptor
 *
 * 인증된 사용자는 이메일을 키로 제한한다.
 * DB 조회 없이 SecurityContext 의 이메일을 그대로 사용해 불필요한 SELECT를 제거했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getRequestURI().equals("/api/health")) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !authentication.getName().equals("anonymousUser")) {
            try {
                String userEmail = authentication.getName();
                if (!rateLimitService.isAllowedByEmail(userEmail)) {
                    throw new BusinessException(CommonErrorCode.RATE_LIMIT_EXCEEDED);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                // Rate Limit 확인 실패 시 서비스 계속 제공
            }
        }

        return true;
    }
}
