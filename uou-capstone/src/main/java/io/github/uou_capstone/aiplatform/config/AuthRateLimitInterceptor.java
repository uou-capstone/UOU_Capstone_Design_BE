package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 미인증 인증 엔드포인트(/api/auth/login, /signup, /refresh)에 IP 기반 Rate Limit을 적용해
 * 브루트포스 공격을 차단한다. 기존 RateLimitInterceptor는 /api/auth/**를 제외하므로 이 인터셉터가 그 공백을 메운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    // 엔드포인트별 분당 허용 횟수 (IP 기준)
    private static final Map<String, Integer> LIMITS = Map.of(
            "/api/auth/login", 10,
            "/api/auth/signup", 5,
            "/api/auth/refresh", 20
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Integer limit = LIMITS.get(request.getRequestURI());
        if (limit == null) {
            return true;
        }

        String ip = resolveClientIp(request);
        try {
            if (!rateLimitService.isAllowedByIp(ip, request.getRequestURI(), limit)) {
                log.warn("Auth rate limit exceeded: ip={}, uri={}, limit={}/min", ip, request.getRequestURI(), limit);
                throw new BusinessException(CommonErrorCode.RATE_LIMIT_EXCEEDED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 장애 시 로그인 전체를 막지 않기 위해 허용(fail-open)하되 경고는 남긴다.
            log.warn("Auth rate limit check failed (fail-open): ip={}, uri={}", ip, request.getRequestURI(), e);
        }
        return true;
    }

    // 리버스 프록시/ngrok 환경을 고려해 실제 클라이언트 IP를 추출한다.
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
