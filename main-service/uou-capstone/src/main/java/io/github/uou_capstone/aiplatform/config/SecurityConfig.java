package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.security.jwt.JwtAuthenticationFilter;
import io.github.uou_capstone.aiplatform.security.oauth.CustomOAuth2UserService;
import io.github.uou_capstone.aiplatform.security.oauth.OAuth2AuthenticationSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

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

            // 세션을 사용하지 않도록 설정 (STATELESS)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // API 경로별 접근 권한 설정
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // 모든 OPTIONS 요청을 허용

                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/ai/callback/**").permitAll()
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
            )

            // 우리가 만든 JWT 필터를 UsernamePasswordAuthenticationFilter 전에 실행
            .addFilterBefore(jwtAuthenticationFilter, BasicAuthenticationFilter.class);

    return http.build();
}

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 프론트엔드 개발자에게 받은 주소를 여기에 추가
        config.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:8000",
                "https://ai-lms.netlify.app/",
                "https://plutean-clement-apheliotropically.ngrok-free.dev/"
                // (여러 개 등록 가능)
        ));

        // 허용할 HTTP 메서드 (전부 허용)
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // 허용할 HTTP 헤더 (전부 허용)
        config.setAllowedHeaders(Arrays.asList("*"));
        // (중요) 쿠키/세션/토큰 인증 정보를 같이 보내려면 true
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // "/api/**"로 시작하는 모든 경로에 이 CORS 설정을 적용
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}