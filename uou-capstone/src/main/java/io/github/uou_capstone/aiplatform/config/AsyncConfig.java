package io.github.uou_capstone.aiplatform.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * 
 * @Async 어노테이션을 사용한 비동기 메서드 실행을 위한 설정
 * 
 * 주요 용도:
 * - Phase 3-5 비동기 처리 (강의 자료 생성)
 * - 시험 채점 비동기 처리
 * - UserFeedbackProfile 생성 비동기 처리
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 강의 자료 생성용 비동기 Executor
     * Phase 3-5 처리에 사용
     * 
     * 설정:
     * - corePoolSize: 5 (기본 스레드 수)
     * - maxPoolSize: 10 (최대 스레드 수)
     * - queueCapacity: 100 (대기 큐 크기)
     * - threadNamePrefix: "material-gen-" (스레드 이름 접두사)
     */
    @Bean(name = "materialGenerationExecutor")
    public Executor materialGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("material-gen-");
        executor.setRejectedExecutionHandler(loggingCallerRunsPolicy("material-gen"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 시험 채점용 비동기 Executor
     */
    @Bean(name = "examGradingExecutor")
    public Executor examGradingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("exam-grading-");
        executor.setRejectedExecutionHandler(loggingCallerRunsPolicy("exam-grading"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 일반 비동기 작업용 Executor
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(loggingCallerRunsPolicy("async-task"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 큐가 꽉 찼을 때 호출 스레드에서 직접 실행(CallerRunsPolicy)하되 경고 로그를 남긴다.
     * AbortPolicy(기본값)는 작업을 버리므로 사용자 요청이 유실될 수 있다.
     */
    private RejectedExecutionHandler loggingCallerRunsPolicy(String poolName) {
        return (runnable, executor) -> {
            log.warn("Async pool [{}] 포화 — caller 스레드에서 직접 실행합니다. pool={}/{}, queue={}",
                    poolName, executor.getActiveCount(), executor.getMaximumPoolSize(),
                    executor.getQueue().size());
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, executor);
        };
    }
}
