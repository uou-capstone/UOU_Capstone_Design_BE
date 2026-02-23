package io.github.uou_capstone.aiplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 시험 채점용 비동기 Executor
     * 시험 응시 및 채점 처리에 사용
     * 
     * 설정:
     * - corePoolSize: 3 (기본 스레드 수)
     * - maxPoolSize: 5 (최대 스레드 수)
     * - queueCapacity: 50 (대기 큐 크기)
     * - threadNamePrefix: "exam-grading-" (스레드 이름 접두사)
     */
    @Bean(name = "examGradingExecutor")
    public Executor examGradingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("exam-grading-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * 일반 비동기 작업용 Executor
     * 기타 비동기 작업에 사용
     * 
     * 설정:
     * - corePoolSize: 2 (기본 스레드 수)
     * - maxPoolSize: 5 (최대 스레드 수)
     * - queueCapacity: 50 (대기 큐 크기)
     * - threadNamePrefix: "async-task-" (스레드 이름 접두사)
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
