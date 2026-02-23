package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
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
 * API 요청에 대해 Rate Limiting을 적용합니다.
 * - 인증된 사용자: 사용자별 제한
 * - 인증되지 않은 사용자: IP 기반 제한 (선택적)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Health Check는 제외
        if (request.getRequestURI().equals("/api/health")) {
            return true;
        }

        // 인증된 사용자의 경우 사용자 ID로 제한
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !authentication.getName().equals("anonymousUser")) {
            try {
                // 사용자 ID 추출 (이메일에서 사용자 조회 필요 시 수정)
                // 현재는 간단하게 이메일을 키로 사용
                String userEmail = authentication.getName();
                Long userId = extractUserId(userEmail);
                
                if (userId != null && !rateLimitService.isAllowed(userId)) {
                    throw new BusinessException(CommonErrorCode.RATE_LIMIT_EXCEEDED);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                // Rate Limit 확인 실패 시 로그만 남기고 계속 진행
                // (Fallback: Rate Limit 실패해도 서비스는 계속 제공)
            }
        }

        return true;
    }

    /**
     * 사용자 ID 추출
     * UserRepository를 통해 이메일로 사용자 ID 조회
     * 
     * 성능 최적화 고려사항:
     * - 향후 Redis 캐싱 추가 가능
     * - 사용자 정보를 SecurityContext에 포함하는 방법도 고려 가능
     */
    private Long extractUserId(String userEmail) {
        try {
            return userRepository.findByEmail(userEmail)
                    .map(user -> user.getId())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("사용자 ID 조회 실패: userEmail={}", userEmail, e);
            return null; // 조회 실패 시 null 반환 (Rate Limit 적용 안 함)
        }
    }
}
