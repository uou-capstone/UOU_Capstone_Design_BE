package io.github.uou_capstone.aiplatform.common.controller;

import io.github.uou_capstone.aiplatform.service.AgentPerformanceLogger;
import io.github.uou_capstone.aiplatform.service.CacheService;
import io.github.uou_capstone.aiplatform.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 모니터링 Controller
 * 
 * 시스템 모니터링 정보를 제공합니다.
 * - 캐시 Hit/Miss 비율
 * - Agent 실행 시간 통계
 * - Rate Limit 통계
 */
@Slf4j
@Tag(name = "모니터링 API", description = "시스템 모니터링 정보 조회 API")
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final CacheService cacheService;
    private final AgentPerformanceLogger agentPerformanceLogger;
    private final RateLimitService rateLimitService;

    @Operation(summary = "캐시 통계 조회", description = "캐시 Hit/Miss 비율을 조회합니다.")
    @GetMapping("/cache")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("profile", cacheService.getProfileMetrics());
        stats.put("session", cacheService.getSessionMetrics());
        stats.put("examSession", cacheService.getExamSessionMetrics());
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Agent 성능 통계 조회", description = "Agent 실행 시간 통계를 조회합니다.")
    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> getAgentStats() {
        Map<String, AgentPerformanceLogger.AgentPerformanceStats> allStats = agentPerformanceLogger.getAllStats();
        
        Map<String, Object> response = new HashMap<>();
        allStats.forEach((agentName, stats) -> {
            Map<String, Object> agentInfo = new HashMap<>();
            agentInfo.put("totalExecutions", stats.getTotalExecutions().get());
            agentInfo.put("successfulExecutions", stats.getSuccessfulExecutions().get());
            agentInfo.put("failedExecutions", stats.getFailedExecutions().get());
            agentInfo.put("averageExecutionTime", stats.getAverageExecutionTime());
            agentInfo.put("minExecutionTime", stats.getMinExecutionTime() == Long.MAX_VALUE ? 0L : stats.getMinExecutionTime());
            agentInfo.put("maxExecutionTime", stats.getMaxExecutionTime());
            agentInfo.put("successRate", stats.getSuccessRate());
            response.put(agentName, agentInfo);
        });
        
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Rate Limit 통계 조회", description = "Rate Limit 통계를 조회합니다.")
    @GetMapping("/rate-limit")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<RateLimitService.RateLimitStats> getRateLimitStats() {
        return ResponseEntity.ok(rateLimitService.getStats());
    }

    @Operation(summary = "전체 모니터링 정보 조회", description = "모든 모니터링 정보를 한 번에 조회합니다.")
    @GetMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Map<String, Object>> getAllMonitoringInfo() {
        Map<String, Object> allInfo = new HashMap<>();
        
        // 캐시 통계
        Map<String, Object> cacheStats = new HashMap<>();
        cacheStats.put("profile", cacheService.getProfileMetrics());
        cacheStats.put("session", cacheService.getSessionMetrics());
        cacheStats.put("examSession", cacheService.getExamSessionMetrics());
        allInfo.put("cache", cacheStats);
        
        // Agent 성능 통계
        Map<String, Object> agentStats = new HashMap<>();
        agentPerformanceLogger.getAllStats().forEach((agentName, stats) -> {
            Map<String, Object> agentInfo = new HashMap<>();
            agentInfo.put("totalExecutions", stats.getTotalExecutions().get());
            agentInfo.put("successfulExecutions", stats.getSuccessfulExecutions().get());
            agentInfo.put("failedExecutions", stats.getFailedExecutions().get());
            agentInfo.put("averageExecutionTime", stats.getAverageExecutionTime());
            agentInfo.put("minExecutionTime", stats.getMinExecutionTime() == Long.MAX_VALUE ? 0 : stats.getMinExecutionTime());
            agentInfo.put("maxExecutionTime", stats.getMaxExecutionTime());
            agentInfo.put("successRate", stats.getSuccessRate());
            agentStats.put(agentName, agentInfo);
        });
        allInfo.put("agents", agentStats);
        
        // Rate Limit 통계
        allInfo.put("rateLimit", rateLimitService.getStats());
        
        return ResponseEntity.ok(allInfo);
    }
}
