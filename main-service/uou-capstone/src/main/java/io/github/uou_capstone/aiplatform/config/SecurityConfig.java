package io.github.uou_capstone.aiplatform.config;

import io.github.uou_capstone.aiplatform.security.jwt.JwtAuthenticationFilter;
import io.github.uou_capstone.aiplatform.security.oauth.CustomOAuth2UserService;
import io.github.uou_capstone.aiplatform.security.oauth.OAuth2AuthenticationSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

            // 세션을 사용하지 않도록 설정 (STATELESS)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // API 경로별 접근 권한 설정
            .authorizeHttpRequests(auth -> auth
                    // 아래 경로들은 인증 없이 누구나 접근 가능
                    .requestMatchers("/swagger-ui.html","/api/auth/**", "/login/**", "/oauth2/**", "/swagger-ui/**", "/api-docs/**").permitAll()
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
}