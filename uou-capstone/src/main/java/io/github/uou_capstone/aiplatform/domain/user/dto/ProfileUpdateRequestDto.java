package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@Schema(description = "프로필 수정 요청 DTO")
public class ProfileUpdateRequestDto {

    @Schema(description = "이름", example = "홍길동")
    private String fullName;

    @Schema(description = "전화번호", example = "010-1234-5678")
    private String phoneNum;

    @Schema(description = "생년월일", example = "2000-01-01")
    private LocalDate birthDate;
}
