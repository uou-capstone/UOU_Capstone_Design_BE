package io.github.uou_capstone.aiplatform.domain.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 비동기 작업 시작 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskResponse {
    private String taskId;
    private String status;  // "accepted"
    private String message;
    private String statusUrl;  // 상태 조회 URL
}
