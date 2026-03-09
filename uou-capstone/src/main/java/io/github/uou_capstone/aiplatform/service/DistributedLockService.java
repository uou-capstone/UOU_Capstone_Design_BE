package io.github.uou_capstone.aiplatform.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 분산 락 서비스
 * 
 * Redis 기반 분산 락을 제공하여 여러 서버 인스턴스 간 중복 실행을 방지합니다.
 * 
 * 주요 사용 사례:
 * - Phase 3-5 비동기 처리 중복 실행 방지
 * - LLM API 호출 중복 방지 (비용 절감)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private final RedissonClient redissonClient;

    /**
     * 분산 락을 사용하여 작업 실행
     * 
     * @param lockKey 락 키 (예: "phase3-5:123")
     * @param waitTime 락 획득 대기 시간 (초)
     * @param leaseTime 락 유지 시간 (초)
     * @param supplier 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock("lock:" + lockKey);
        
        try {
            // 락 획득 시도
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            
            if (!acquired) {
                log.warn("분산 락 획득 실패: lockKey={}, waitTime={}s", lockKey, waitTime);
                throw new BusinessException(
                    CommonErrorCode.OPERATION_IN_PROGRESS,
                    "작업이 이미 진행 중입니다. 잠시 후 다시 시도해주세요."
                );
            }
            
            log.debug("분산 락 획득 성공: lockKey={}", lockKey);
            
            try {
                // 작업 실행
                return supplier.get();
            } finally {
                // 락 해제 (현재 스레드가 보유한 경우에만)
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                    log.debug("분산 락 해제: lockKey={}", lockKey);
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("분산 락 획득 중단: lockKey={}", lockKey, e);
            throw new BusinessException(
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                "작업이 중단되었습니다."
            );
        } catch (BusinessException e) {
            // BusinessException은 그대로 전파
            throw e;
        } catch (Exception e) {
            log.error("분산 락 작업 실행 중 오류: lockKey={}", lockKey, e);
            throw new BusinessException(
                CommonErrorCode.INTERNAL_SERVER_ERROR,
                "작업 실행 중 오류가 발생했습니다: " + e.getMessage()
            );
        }
    }

    /**
     * 분산 락을 사용하여 작업 실행 (반환값 없음)
     * 
     * @param lockKey 락 키
     * @param waitTime 락 획득 대기 시간 (초)
     * @param leaseTime 락 유지 시간 (초)
     * @param runnable 실행할 작업
     */
    public void executeWithLock(String lockKey, long waitTime, long leaseTime, Runnable runnable) {
        executeWithLock(lockKey, waitTime, leaseTime, () -> {
            runnable.run();
            return null;
        });
    }
}
