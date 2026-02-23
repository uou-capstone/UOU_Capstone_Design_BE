package io.github.uou_capstone.aiplatform.domain.material.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AiFileResponseDto {
    private String filename;
    private String path; // ai-service가 저장한 파일 경로
}