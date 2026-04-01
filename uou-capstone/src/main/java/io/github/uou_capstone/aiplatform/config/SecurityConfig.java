package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.security.exception.JwtAccessDeniedHandler;
import io.github.uou_capstone.aiplatform.security.exception.RestAuthenticationEntryPoint;
import io.github.uou_capstone.aiplatform.security.jwt.JwtAuthenticationFilter;
import io.github.uou_capstone.aiplatform.security.oauth.CustomOAuth2UserService;
import io.github.uou_capstone.aiplatform.security.oauth.OAuth2AuthenticationFailureHandler;
import io.github.uou_capstone.aiplatform.security.oauth.OAuth2AuthenticationSuccessHandler;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
            // CSRF, Form Login, HTTP Basic 인증 비활성화
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(restAuthenticationEntryPoint)
                    .accessDeniedHandler(jwtAccessDeniedHandler)
            )

            // 세션을 사용하지 않도록 설정 (STATELESS)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // API 경로별 접근 권한 설정
            .authorizeHttpRequests(auth -> auth
                    // SSE/스트리밍용 Tomcat async dispatch 허용 (재인증 없이 통과)
                    // Flux<ServerSentEvent> 응답 시 AsyncContextImpl이 2차 dispatch하는 경우를 허용
                    .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // 모든 OPTIONS 요청을 허용

                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/health").permitAll() // Health Check 허용
                    .requestMatchers("/api/ai/callback/**").permitAll()
                    .requestMatchers("/error").permitAll()
                    // 아래 경로들은 인증 없이 누구나 접근 가능
                    .requestMatchers("/swagger-ui.html","/login/**", "/oauth2/**", "/swagger-ui/**",
                            "/api-docs/**", "/api/lectures/").permitAll()
                    // 그 외 모든 경로는 인증된 사용자만 접근 가능
                    .anyRequest().authenticated()
            )

            // OAuth2 로그인 설정
            .oauth2Login(oauth2 -> oauth2
                    .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                    .successHandler(oAuth2AuthenticationSuccessHandler)
                    .failureHandler(oAuth2AuthenticationFailureHandler)
            )

            // 우리가 만든 JWT 필터를 UsernamePasswordAuthenticationFilter 전에 실행
            .addFilterBefore(jwtAuthenticationFilter, BasicAuthenticationFilter.class);

    return http.build();
}

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 프론트엔드 출처(Origin). 슬래시(/) 없이 도메인만 입력
        // 127.0.0.1 추가: localhost와 다른 Host로 인식되어 CORS 차단될 수 있음
        config.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8000",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8000",
                "https://ai-lms.netlify.app",
                "https://plutean-clement-apheliotropically.ngrok-free.dev"
        ));

        // 허용할 HTTP 메서드 (전부 허용)
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        
        // 허용할 HTTP 헤더 (명시적으로 지정 - 더 안전함)
        config.setAllowedHeaders(Arrays.asList(
                "*",  // 모든 헤더 허용
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));
        
        // (중요) 쿠키/세션/토큰 인증 정보를 같이 보내려면 true
        config.setAllowCredentials(true);
        
        // Preflight 요청 캐싱 시간 (초 단위) - 1시간
        config.setMaxAge(3600L);
        
        // 노출할 응답 헤더 (프론트엔드에서 접근 가능한 헤더)
        config.setExposedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Total-Count"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/**", config);  // 미리보기 등 기타 경로도 CORS 적용
        return source;
    }
}
