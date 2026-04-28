# service (cross-cutting)

도메인 경계를 넘는 공통 서비스. 도메인 패키지(`domain/*/service`)와 구분되며, 어느 한 도메인에 귀속시키기 어려운 인프라성 빈을 모아둠.

> ℹ️ 도메인별 서비스는 각 `domain/<name>/service/` 에 존재. 이 패키지는 **여러 도메인이 공통으로 의존하는** 빈만 보유.

---

## 빈 카탈로그

| 빈 | 한 줄 요약 | Redis 키 prefix |
|---|---|---|
| `CurrentUserResolver` | 요청당 한 번만 User+Student+Teacher 조회 (`@RequestScope`) | — |
| `AsyncTaskService` | 비동기 작업 상태 추적 (`task` 도메인 README 참조) | `sb:task:` |
| `TokenBlacklistService` | 로그아웃한 JWT 블랙리스트 | `sb:blacklist:token:` |
| `RateLimitService` | API 호출 제한 (사용자별/엔드포인트별/IP+엔드포인트별) | `sb:rate_limit:` |
| `CacheService` | 도메인 캐시 (Profile/Session/ExamSession + 범용 set/get) | `sb:cache:profile:` / `sb:cache:session:` / `sb:cache:exam:` / 호출자 지정 |
| `DistributedLockService` | Redisson 기반 분산 락 (`executeWithLock`) | `sb:lock:` |
| `SessionRecoveryService` | GenerationSession Phase 롤백 + ExamSession FAILED→GENERATING 복구 | — |
| `AgentPerformanceLogger` | Agent 실행 시간·성공률 in-memory 통계 | — |
| `CacheMetrics` | Hit/Miss 통계 DTO (`CacheService` 가 보유) | — |

---

## `CurrentUserResolver` — 가장 중요

**프로젝트 전반에서 사용자 조회의 표준 진입점**. CLAUDE.md 의 "현재 유저 조회" 규칙이 이 빈을 가리킴.

```java
@Service
@RequestScope                  // 요청마다 새 인스턴스 → 스레드 안전
public class CurrentUserResolver {
    public User getUser()       // 캐시된 User 반환 (없으면 DB 1회 조회)
    public Student getStudent() // user.student null 이면 MEMBER_NOT_FOUND
    public Teacher getTeacher() // user.teacher null 이면 MEMBER_NOT_FOUND
}
```

DB 조회는 `userRepository.findByEmailWithRoles(email)` 1회 — `@EntityGraph` 로 Student/Teacher 까지 fetch. 같은 요청 내 두 번째 호출부터는 in-memory 캐시.

**금지 사항 (CLAUDE.md):**
```java
// ❌
User user = userRepository.findByEmail(email).orElseThrow(...);

// ✅
User user = currentUserResolver.getUser();
```

**예외 (`@Async`):** `@RequestScope` 빈은 비동기 스레드에서 접근 불가 → 컨트롤러에서 `userEmail` / `userId` 인자로 전달하거나 `userRepository.findByEmailWithRoles(email)` 1회 허용.

---

## Redis 키 네임스페이스 — `sb:` prefix

이 패키지에서 만든 모든 Redis 키는 `sb:` (spring boot) 로 시작. FastAPI 측은 `fa:` / `shared:` 사용. 충돌 회피 + 디버깅 용이.

| 패턴 | 용도 | TTL |
|---|---|---|
| `sb:task:{taskId}` | 비동기 작업 상태 | 24h (갱신 시 재설정) |
| `sb:blacklist:token:{token}` | JWT 블랙리스트 | 토큰 만료 잔여시간 |
| `sb:rate_limit:{kind}:{key}:{minute}` | 분당 호출 카운터 | 1분 |
| `sb:cache:profile:{contentHash}` | 시험 Profile 캐시 | 24h |
| `sb:cache:session:{sessionId}` | 세션 상태 캐시 | 1h |
| `sb:cache:exam:{examSessionId}` | 시험 세션 캐시 | 30m |
| `sb:lock:{lockKey}` | Redisson 분산 락 | leaseTime |

새 키 추가 시 `sb:` 유지. 도메인별 prefix(`sb:cache:profile:` 등)로 의도 표현.

---

## Fallback 정책 — Redis 장애 시 동작

**모든 Redis 호출이 try-catch 로 감싸져** 있고, 실패 시:

| 빈 | Fallback |
|---|---|
| `CacheService.get*` | `Optional.empty()` (캐시 미스로 처리) |
| `CacheService.cache*` | 무시 (다음 호출 그냥 진행) |
| `RateLimitService.isAllowed*` | **`true` 반환 — 통과시킴** (가용성 우선) |
| `TokenBlacklistService.isBlacklisted` | `false` 반환 (블랙리스트 미존재로 처리) |
| `AsyncTaskService.{create,update,save}` | `BusinessException(INTERNAL_SERVER_ERROR)` — 작업 추적 불가능하므로 강제 종료 |
| `DistributedLockService.executeWithLock` | `BusinessException(INTERNAL_SERVER_ERROR)` |

> ⚠️ **RateLimit fallback 이 `true`** — Redis 다운 시 무제한 통과. 보안 영향 인지하고 사용. 운영 환경에서 Redis 다운은 다른 모니터링이 먼저 잡아야 함.

---

## `RateLimitService` — 키 종류 4가지

```java
isAllowed(userId)                          // user:{id}
isAllowed(userId, maxRequests)             // user:{id} (커스텀 limit)
isAllowedByEmail(email)                    // email:{email} — DB 조회 없이 SecurityContext name 그대로
isAllowedForEndpoint(endpoint, maxRequests)// endpoint:{path}
isAllowedByIp(ip, endpoint, maxRequests)   // ip:{endpoint}:{ip} — 브루트포스 방어용
```

- **분당 카운터** — 키 자체에 분 (`yyyy-MM-ddTHH:mm`) 포함. 1분마다 새 키 → TTL 만료로 자연 리셋
- 엔드포인트별 키 분리 — `/login` 실패가 `/signup` 한도에 영향 X

호출은 `config/RateLimitInterceptor` (사용자별) + `config/AuthRateLimitInterceptor` (IP+엔드포인트) 가 담당.

---

## `DistributedLockService` — Phase 3-5 중복 실행 방지

```java
distributedLockService.executeWithLock(
    "phase3-5:" + sessionId,    // lockKey
    5L,                          // waitTime: 락 획득 대기 (초)
    1200L,                       // leaseTime: 락 유지 (초) — Phase 3-5 max 20분
    () -> {
        // 한 번에 한 인스턴스만 실행되어야 하는 작업
    }
);
```

**Redisson** (`org.redisson:redisson-spring-boot-starter`) 사용. 단일 Redis 노드에서도 동작. 클러스터 시 RedLock 옵션 검토.

락 획득 실패 → `OPERATION_IN_PROGRESS` 에러. 클라이언트 재시도 권장 메시지 포함.

---

## `SessionRecoveryService` — Phase 롤백 정책

`GenerationSession` 실패 시 한 단계 이전 Phase 로 롤백:

| 실패 Phase | 롤백 대상 | 진행률 |
|---|---|---|
| PHASE1 | PHASE1 (유지) | 20 |
| PHASE2 | PHASE1 | 20 |
| PHASE3 | PHASE2 | 40 |
| PHASE4 | PHASE3 | 60 |
| PHASE5 | PHASE4 | 80 |
| COMPLETED | PHASE5 | 100 (이미 완료된 상태) |
| FAILED | PHASE1 | 0 |

`ExamSession` 은 단순 `markAsFailed()` / 복구 시 `GENERATING` 으로 되돌림.

복구 호출 위치:
- `exam.controller.ExamGenerationController.recoverExamSession` → `recoverExamSession`
- (material 측은 현재 자동 복구 호출 없음 — 수동 호출만)

---

## 상시 주의사항 (Gotcha)

### `@RequestScope` 빈 주입 시 프록시 사용
`CurrentUserResolver` 는 싱글톤 빈에 주입되어도 동작하도록 Spring 이 프록시로 감쌈. `@Autowired` 필드 그대로 사용 OK. 단 **테스트에서 직접 인스턴스화** 시 DI 컨테이너 없이는 RequestContext 가 없어 `getUser()` 호출이 깨짐 — Mock 사용.

### Redis 호출은 모두 try-catch
신규 메서드 추가 시 동일 패턴 유지. **Redis 장애를 도메인 로직으로 전파시키지 말 것** (가용성 우선). 단 작업 추적/락 등 핵심 기능은 예외.

### `AgentPerformanceLogger` 는 in-memory only
서버 재시작 시 통계 소실. 영속 통계가 필요하면 별도 저장 필요. 현재는 디버그용.

### `RateLimit` 은 minute 경계에 burst 가능
키에 `yyyy-MM-ddTHH:mm` 포함 → 59초에 100회 + 0초에 100회 = 1초 사이 200회 가능. 슬라이딩 윈도우가 아닌 fixed window. 엄격한 제한 필요하면 sliding window 알고리즘으로 재구현.

### `CacheService.set/get` 는 ObjectMapper 직렬화
`@JsonAnyGetter` 등 커스텀 직렬화 의존하는 객체는 캐시 후 복원 시 데이터 손실 가능. 주로 `Map<String,Object>` 또는 단순 DTO 만 캐시.

### `TokenBlacklistService.removeFromBlacklist` 사용 금지
정상 흐름에서 호출하지 않음 — 토큰 만료 자연 정리에 의존. 잘못 호출하면 로그아웃 무효화. 디버그 전용.

### Redisson 클라이언트 빈
`config/RedisConfig` (또는 별도) 에서 `RedissonClient` 빈 정의 필요. 없으면 `DistributedLockService` 가 주입 실패.

---

## 주요 파일

- `CurrentUserResolver.java` — `@RequestScope` 사용자 캐시 (전 도메인 사용)
- `AsyncTaskService.java` — Redis 기반 task 추적 (`task` 도메인 README 참조)
- `TokenBlacklistService.java` — JWT 블랙리스트 (`security` README 참조)
- `RateLimitService.java` — 분당 호출 제한
- `CacheService.java` — 도메인 캐시 + 범용 set/get
- `CacheMetrics.java` — Hit/Miss 통계 DTO
- `DistributedLockService.java` — Redisson 분산 락
- `SessionRecoveryService.java` — Generation/Exam 세션 복구
- `AgentPerformanceLogger.java` — Agent 실행 통계 (`agent/AbstractAgent` 가 사용)

### 의존
- `domain/user/repository/UserRepository.java` — `findByEmailWithRoles`
- `domain/user/entity/{User,Teacher,Student}` — `CurrentUserResolver`
- `domain/exam/{entity,repository}` — `SessionRecoveryService`
- `domain/material/generation/{GenerationSession,GenerationSessionRepository,GenerationPhase}` — `SessionRecoveryService`
- `common/error/CommonErrorCode.{MEMBER_NOT_FOUND, SESSION_NOT_FOUND, OPERATION_IN_PROGRESS, INTERNAL_SERVER_ERROR}`
- `StringRedisTemplate` (Spring Data Redis), `RedissonClient` (Redisson)
