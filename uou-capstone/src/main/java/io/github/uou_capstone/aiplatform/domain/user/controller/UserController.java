package io.github.uou_capstone.aiplatform.domain.user.controller;

import io.github.uou_capstone.aiplatform.domain.user.dto.AccountDeleteRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.MyInfoResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.PasswordChangeRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.ProfileUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "사용자 API", description = "사용자 정보 조회, 수정, 탈퇴 관련 API")
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
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody PasswordChangeRequestDto requestDto) {
        userService.changePassword(requestDto);
        return ResponseEntity.ok(Map.of("message", "비밀번호가 성공적으로 변경되었습니다."));
    }

    @Operation(summary = "프로필 수정", description = "로그인한 사용자의 프로필 정보(이름, 전화번호, 생년월일)를 수정합니다.")
    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MyInfoResponseDto> updateProfile(@Valid @RequestBody ProfileUpdateRequestDto requestDto) {
        MyInfoResponseDto updatedProfile = userService.updateProfile(requestDto);
        return ResponseEntity.ok(updatedProfile);
    }

    @Operation(summary = "회원 탈퇴", description = "로그인한 사용자의 계정을 삭제합니다. 비밀번호 확인이 필요합니다.")
    @DeleteMapping("/account")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> deleteAccount(@Valid @RequestBody AccountDeleteRequestDto requestDto) {
        userService.deleteAccount(requestDto.getPassword());
        return ResponseEntity.ok(Map.of("message", "회원 탈퇴가 완료되었습니다."));
    }
}

