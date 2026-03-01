package io.github.uou_capstone.aiplatform.domain.user.controller;

import io.github.uou_capstone.aiplatform.domain.user.dto.LoginRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.RefreshTokenRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.SignUpRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.TokenResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "인증 API", description = "사용자 회원가입, 로그인, 로그아웃, 토큰 갱신 처리")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 역할을 받아 회원가입을 진행합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @ApiResponse(responseCode = "409", description = "이미 존재하는 이메일 (Conflict)")
    })
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@Valid @RequestBody SignUpRequestDto requestDto) {
        authService.signup(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("회원가입이 성공적으로 완료되었습니다.");
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 인증 토큰(JWT)을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
            @ApiResponse(responseCode = "401", description = "인증 실패 (Unauthorized)")
    })
    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(@Valid @RequestBody LoginRequestDto requestDto) {
        TokenResponseDto token = authService.login(requestDto);
        return ResponseEntity.ok(token);
    }

    @Operation(summary = "로그아웃", description = "현재 사용 중인 액세스 토큰을 블랙리스트에 추가하여 로그아웃합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        // Authorization 헤더에서 토큰 추출
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            String token = bearerToken.substring(7);
            authService.logout(token);
        }
        
        return ResponseEntity.ok(Map.of("message", "로그아웃이 완료되었습니다."));
    }

    @Operation(summary = "토큰 갱신", description = "리프레시 토큰을 사용하여 새로운 액세스 토큰을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 갱신 성공"),
            @ApiResponse(responseCode = "401", description = "리프레시 토큰이 만료되었거나 유효하지 않음")
    })
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponseDto> refreshToken(@Valid @RequestBody RefreshTokenRequestDto requestDto) {
        TokenResponseDto token = authService.refreshToken(requestDto);
        return ResponseEntity.ok(token);
    }
}
