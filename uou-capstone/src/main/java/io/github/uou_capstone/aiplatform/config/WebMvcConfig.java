package io.github.uou_capstone.aiplatform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 설정
 * 
 * Interceptor 등록 및 기타 Web MVC 설정
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")  // /api/** 경로에만 적용
                .excludePathPatterns(
                        "/api/health",  // Health Check 제외
                        "/api/auth/**",  // 인증 API 제외
                        "/api-docs/**",  // API 문서 제외
                        "/swagger-ui/**"  // Swagger UI 제외
                );
    }
}
