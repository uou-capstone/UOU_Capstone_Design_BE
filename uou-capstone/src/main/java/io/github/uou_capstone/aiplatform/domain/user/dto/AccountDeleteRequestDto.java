package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "회원 탈퇴 요청 DTO")
public class AccountDeleteRequestDto {

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Schema(description = "현재 비밀번호 (탈퇴 확인용)", example = "currentPassword123!", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
