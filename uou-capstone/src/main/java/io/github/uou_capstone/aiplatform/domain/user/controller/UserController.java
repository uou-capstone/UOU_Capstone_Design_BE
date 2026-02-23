package io.github.uou_capstone.aiplatform.domain.user.controller;

import io.github.uou_capstone.aiplatform.domain.user.dto.MyInfoResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.PasswordChangeRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자 API", description = "사용자 정보 조회 및 수정 관련 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 조회합니다.")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MyInfoResponseDto> getMyInfo() {
        MyInfoResponseDto myInfo = userService.getMyInfo();
        return ResponseEntity.ok(myInfo);
    }

    @Operation(summary = "비밀번호 변경", description = "로그인한 사용자의 비밀번호를 변경합니다.")
    @PutMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<String> changePassword(@Valid @RequestBody PasswordChangeRequestDto requestDto) {
        userService.changePassword(requestDto);
        return ResponseEntity.ok("비밀번호가 성공적으로 변경되었습니다.");
    }
}

