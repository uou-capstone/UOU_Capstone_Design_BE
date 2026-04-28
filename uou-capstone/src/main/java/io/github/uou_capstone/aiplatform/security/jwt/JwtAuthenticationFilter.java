package io.github.uou_capstone.aiplatform.security.jwt;

import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.service.TokenBlacklistService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        try {
            if (token != null) {
                if (tokenBlacklistService.isBlacklisted(token)) {
                    request.setAttribute("exception", "INVALID_TOKEN");
                    filterChain.doFilter(request, response);
                    return;
                }

                jwtTokenProvider.validateToken(token);
                Authentication authentication = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (ExpiredJwtException e) {
            request.setAttribute("exception", "TOKEN_EXPIRED");
        } catch (BusinessException e) {
            log.warn("[JWT] 토큰 인증 정보가 유효하지 않습니다: path={}, error={}",
                    request.getRequestURI(), e.getMessage());
            request.setAttribute("exception", "INVALID_TOKEN");
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute("exception", "INVALID_TOKEN");
        } catch (Exception e) {
            log.warn("[JWT] 토큰 처리 중 예외 발생: path={}, error={}",
                    request.getRequestURI(), e.getMessage());
            request.setAttribute("exception", "INVALID_TOKEN");
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
