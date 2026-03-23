package io.github.uou_capstone.aiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.task.entity.TaskStatus;
import io.github.uou_capstone.aiplatform.domain.task.dto.TaskStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 비동기 작업 상태 추적 서비스 (Redis 기반)
 * 
 * Redis를 사용하여 작업 상태를 추적합니다.
 * - 작업 상태: QUEUED, PROCESSING, COMPLETED, FAILED
 * - 진행률: 0-100
 * - 메시지: 현재 작업 단계 설명
 * - 결과: 완료 시 결과 데이터 (JSON)
 * 
 * TTL: 24시간 (작업 완료 후 자동 삭제)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TASK_PREFIX = "sb:task:";
    private static final Duration TASK_TTL = Duration.ofHours(24); // 24시간

    /**
     * 작업 상태 초기화
     * 
     * @param taskId 작업 ID
     * @param message 초기 메시지
     * @return 생성된 작업 정보
     */
    public TaskStatusResponse createTask(String taskId, String message) {
        TaskStatusResponse task = TaskStatusResponse.builder()
                .taskId(taskId)
                .status(TaskStatus.QUEUED.name().toLowerCase())
                .progress(0)
                .message(message)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        saveTask(task);
        log.info("Task created: taskId={}, status={}", taskId, task.getStatus());
        return task;
    }

    /**
     * 작업 상태 업데이트
     * 
     * @param taskId 작업 ID
     * @param status 작업 상태
     * @param progress 진행률 (0-100)
     * @param message 메시지
     */
    public void updateTaskStatus(String taskId, TaskStatus status, Integer progress, String message) {
        updateTaskStatus(taskId, status, progress, message, null);
    }

    /**
     * 작업 상태 업데이트 (결과 포함)
     * 
     * @param taskId 작업 ID
     * @param status 작업 상태
     * @param progress 진행률 (0-100)
     * @param message 메시지
     * @param result 결과 데이터 (JSON 또는 텍스트)
     */
    public void updateTaskStatus(String taskId, TaskStatus status, Integer progress, String message, String result) {
        TaskStatusResponse task = getTask(taskId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.TASK_NOT_FOUND, "작업을 찾을 수 없습니다: " + taskId));
        
        task.setStatus(status.name().toLowerCase());
        task.setProgress(progress);
        task.setMessage(message);
        task.setResult(result);
        task.setUpdatedAt(LocalDateTime.now());
        
        saveTask(task);
        log.debug("Task updated: taskId={}, status={}, progress={}%", taskId, status, progress);
    }

    /**
     * 작업 상태 조회
     * 
     * @param taskId 작업 ID
     * @return 작업 상태 정보
     */
    public TaskStatusResponse getTaskStatus(String taskId) {
        return getTask(taskId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.TASK_NOT_FOUND, "작업을 찾을 수 없습니다: " + taskId));
    }

    /**
     * 작업 삭제
     * 
     * @param taskId 작업 ID
     */
    public void deleteTask(String taskId) {
        String key = TASK_PREFIX + taskId;
        redisTemplate.delete(key);
        log.debug("Task deleted: taskId={}", taskId);
    }

    /**
     * Redis에서 작업 조회
     */
    private Optional<TaskStatusResponse> getTask(String taskId) {
        try {
            String key = TASK_PREFIX + taskId;
            String taskJson = redisTemplate.opsForValue().get(key);
            
            if (taskJson == null) {
                return Optional.empty();
            }
            
            TaskStatusResponse task = objectMapper.readValue(taskJson, TaskStatusResponse.class);
            return Optional.of(task);
        } catch (Exception e) {
            log.error("Failed to get task from Redis: taskId={}", taskId, e);
            return Optional.empty();
        }
    }

    /**
     * Redis에 작업 저장
     */
    private void saveTask(TaskStatusResponse task) {
        try {
            String key = TASK_PREFIX + task.getTaskId();
            String taskJson = objectMapper.writeValueAsString(task);
            redisTemplate.opsForValue().set(key, taskJson, TASK_TTL);
        } catch (Exception e) {
            log.error("Failed to save task to Redis: taskId={}", task.getTaskId(), e);
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR, "작업 상태 저장 실패");
        }
    }
}
