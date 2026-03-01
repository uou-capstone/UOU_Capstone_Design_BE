package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "토큰 갱신 요청 DTO")
public class RefreshTokenRequestDto {

    @NotBlank(message = "리프레시 토큰은 필수입니다.")
    @Schema(description = "리프레시 토큰", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...", requiredMode = Schema.RequiredMode.REQUIRED)
    private String refreshToken;

    public RefreshTokenRequestDto(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
