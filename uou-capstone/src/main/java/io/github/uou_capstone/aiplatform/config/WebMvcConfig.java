package io.github.uou_capstone.aiplatform.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
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
    private final AuthRateLimitInterceptor authRateLimitInterceptor;

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

        // 로그인/회원가입/리프레시/이메일 중복 확인은 IP 기반 브루트포스·enumeration 방어가 별도로 필요하다.
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns(
                        "/api/auth/login",
                        "/api/auth/signup",
                        "/api/auth/refresh",
                        "/api/auth/check-email"
                );
    }

    @Bean(name = "mvcAsyncTaskExecutor")
    public AsyncTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mvc-async-");
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
        configurer.setDefaultTimeout(60_000L);
    }
}
