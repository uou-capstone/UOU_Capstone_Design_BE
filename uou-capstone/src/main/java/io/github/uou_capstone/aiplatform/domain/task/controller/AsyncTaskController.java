package io.github.uou_capstone.aiplatform.domain.task.controller;

import io.github.uou_capstone.aiplatform.domain.task.dto.AsyncTaskResponse;
import io.github.uou_capstone.aiplatform.domain.task.dto.TaskStatusResponse;
import io.github.uou_capstone.aiplatform.service.AsyncTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비동기 작업 상태 조회 Controller
 * 
 * 비동기 작업의 상태를 조회하는 API를 제공합니다.
 * - 작업 상태 조회: GET /api/tasks/{taskId}/status
 */
@Tag(name = "비동기 작업 상태 조회", description = "비동기 작업의 진행 상태를 조회하는 API")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class AsyncTaskController {

    private final AsyncTaskService asyncTaskService;

    @Operation(summary = "작업 상태 조회", description = "비동기 작업의 현재 상태를 조회합니다.")
    @GetMapping("/{taskId}/status")
    public ResponseEntity<TaskStatusResponse> getTaskStatus(@PathVariable String taskId) {
        TaskStatusResponse status = asyncTaskService.getTaskStatus(taskId);
        return ResponseEntity.ok(status);
    }
}
