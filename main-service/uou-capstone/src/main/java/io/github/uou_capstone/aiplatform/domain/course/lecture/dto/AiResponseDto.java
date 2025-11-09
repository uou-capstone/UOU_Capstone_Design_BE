package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 *  (FastAPI → Spring 이 응답할 결과)
 *  이 DTO는 GeneratedContent의 ERD를 기반으로 합니다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiResponseDto {
    private String contentType; // 예: "SCRIPT", "SUMMARY"
    private String contentData; // 예: "생성된 텍스트 대본..."
    private String materialReferences; // 예: "참조한 자료 ID JSON..."
}