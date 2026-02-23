package io.github.uou_capstone.aiplatform.domain.task.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 비동기 작업 상태 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusResponse {
    private String taskId;
    private String status;  // "queued", "processing", "completed", "failed"
    private Integer progress;  // 0-100
    private String message;
    private String result;  // 완료 시 결과 (JSON 또는 텍스트)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
