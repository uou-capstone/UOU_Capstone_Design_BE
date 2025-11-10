package io.github.uou_capstone.aiplatform.domain.user;

import io.github.uou_capstone.aiplatform.domain.user.dto.LoginRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.MyInfoResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.SignUpRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.TokenResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "인증 API", description = "사용자 회원가입 및 로그인 처리")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 이름, 역할을 받아 회원가입을 진행합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @ApiResponse(responseCode = "409", description = "이미 존재하는 이메일 (Conflict)")
    })
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignUpRequestDto requestDto) {
        authService.signup(requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body("회원가입이 성공적으로 완료되었습니다.");
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 인증 토큰(JWT)을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공 및 토큰 발급"),
            @ApiResponse(responseCode = "401", description = "인증 실패 (Unauthorized)")
    })
    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(@RequestBody LoginRequestDto requestDto) {
        TokenResponseDto token = authService.login(requestDto);
        return ResponseEntity.ok(token);
    }

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 조회합니다.")
    @GetMapping("/me")
    // @PreAuthorize("isAuthenticated()") // 이미 SecurityConfig에서 /api/auth/** 외에는 인증을 요구하므로 생략 가능
    public ResponseEntity<MyInfoResponseDto> getMyInfo() {
        MyInfoResponseDto myInfo = userService.getMyInfo();
        return ResponseEntity.ok(myInfo);
    }


}