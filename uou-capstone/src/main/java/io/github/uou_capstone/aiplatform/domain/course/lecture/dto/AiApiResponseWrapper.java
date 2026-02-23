package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ai-service의 동기 응답을 받기 위한 '껍데기' DTO
 * (예: {"status": "ok", "result": [...]})
 */
@Getter
@NoArgsConstructor
public class AiApiResponseWrapper {

    private String status;

    // FastAPI의 "result" 필드에 실제 데이터(AiResponseDto 목록)가 매핑됩니다.
    @JsonProperty("result")
    private List<AiResponseDto> results;
}