package io.github.uou_capstone.aiplatform.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 성능 로깅 서비스
 * 
 * Agent 실행 시간을 추적하고 통계를 수집합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPerformanceLogger {

    // ========== 성능 통계 ==========
    private final Map<String, AgentPerformanceStats> agentStats = new ConcurrentHashMap<>();

    /**
     * Agent 실행 시작
     * 
     * @param agentName Agent 이름
     * @return 시작 시간 (나중에 종료 시간과 비교)
     */
    public LocalDateTime startExecution(String agentName) {
        return LocalDateTime.now();
    }

    /**
     * Agent 실행 종료 및 로깅
     * 
     * @param agentName Agent 이름
     * @param startTime 시작 시간
     * @param success 성공 여부
     */
    public void endExecution(String agentName, LocalDateTime startTime, boolean success) {
        LocalDateTime endTime = LocalDateTime.now();
        Duration duration = Duration.between(startTime, endTime);
        long milliseconds = duration.toMillis();

        // 통계 업데이트
        AgentPerformanceStats stats = agentStats.computeIfAbsent(
                agentName, 
                k -> new AgentPerformanceStats(agentName)
        );
        stats.recordExecution(milliseconds, success);

        // 로깅
        if (success) {
            log.info("[Agent Performance] {} executed in {}ms (avg: {}ms, total: {})", 
                    agentName, milliseconds, stats.getAverageExecutionTime(), stats.getTotalExecutions());
        } else {
            log.warn("[Agent Performance] {} failed after {}ms (avg: {}ms, total: {})", 
                    agentName, milliseconds, stats.getAverageExecutionTime(), stats.getTotalExecutions());
        }
    }

    /**
     * Agent 성능 통계 조회
     * 
     * @param agentName Agent 이름
     * @return 성능 통계
     */
    public AgentPerformanceStats getStats(String agentName) {
        return agentStats.getOrDefault(agentName, new AgentPerformanceStats(agentName));
    }

    /**
     * 모든 Agent 성능 통계 조회
     */
    public Map<String, AgentPerformanceStats> getAllStats() {
        return Map.copyOf(agentStats);
    }

    /**
     * Agent 성능 통계
     */
    @lombok.Data
    public static class AgentPerformanceStats {
        private final String agentName;
        private final AtomicLong totalExecutions = new AtomicLong(0);
        private final AtomicLong successfulExecutions = new AtomicLong(0);
        private final AtomicLong failedExecutions = new AtomicLong(0);
        private final AtomicLong totalExecutionTime = new AtomicLong(0);
        private volatile long minExecutionTime = Long.MAX_VALUE;
        private volatile long maxExecutionTime = 0;

        public AgentPerformanceStats(String agentName) {
            this.agentName = agentName;
        }

        /**
         * 실행 기록
         */
        public synchronized void recordExecution(long milliseconds, boolean success) {
            totalExecutions.incrementAndGet();
            if (success) {
                successfulExecutions.incrementAndGet();
            } else {
                failedExecutions.incrementAndGet();
            }
            
            totalExecutionTime.addAndGet(milliseconds);
            
            if (milliseconds < minExecutionTime) {
                minExecutionTime = milliseconds;
            }
            if (milliseconds > maxExecutionTime) {
                maxExecutionTime = milliseconds;
            }
        }

        /**
         * 평균 실행 시간 (밀리초)
         */
        public double getAverageExecutionTime() {
            long total = totalExecutions.get();
            if (total == 0) {
                return 0.0;
            }
            return (double) totalExecutionTime.get() / total;
        }

        /**
         * 성공률 (0.0 ~ 1.0)
         */
        public double getSuccessRate() {
            long total = totalExecutions.get();
            if (total == 0) {
                return 0.0;
            }
            return (double) successfulExecutions.get() / total;
        }
    }
}
