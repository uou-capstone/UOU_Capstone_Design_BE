# task 도메인

비동기 작업 진행 상태 추적. 시험 생성·자료 생성 등의 `@Async` 작업이 진행률·결과를 Redis 에 적재하고, FE 가 폴링.
이 도메인 작업 시 참고용 상시 메모.

> ℹ️ 이 도메인은 **DTO/Enum/컨트롤러만 보유**. 실제 상태 저장 서비스(`AsyncTaskService`)는 `aiplatform.service` 패키지(루트 service)에 있음 — 도메인 외부.

---

## 개요

- **TaskStatus** Enum: `QUEUED` → `PROCESSING` → `COMPLETED` / `FAILED`
- **저장소**: Redis (`StringRedisTemplate`, key 형식 `sb:task:{taskId}`, TTL **24시간**)
- **taskId**: UUID — 호출 컨트롤러가 발급. DB 영속 X.
- **JSON 페이로드**: `TaskStatusResponse` 통째로 JSON 직렬화 후 한 키에 저장

---

## 주요 플로우

prefix: `/api/tasks`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET /{taskId}/status` | 익명 (인증 불필요) | 진행률·메시지·결과 폴링 |

> ⚠️ 컨트롤러에 `@PreAuthorize` 없음. taskId 가 UUID 이고 24h TTL 이라 추측 공격 비용이 충분히 큼 — 의도된 단순성. 민감한 결과(`result`) 가 들어가는 경우 별도 인가 체크 필요.

### 비동기 작업 시작 시 응답 형식 (`AsyncTaskResponse`)
시작 엔드포인트(예: `POST /api/exams/generation/async`, `POST /api/materials/generation/async`)는 즉시 다음을 반환:
```json
{
  "taskId": "uuid-...",
  "status": "accepted",
  "message": "...이 시작되었습니다.",
  "statusUrl": "/api/tasks/uuid-.../status"
}
```
FE 는 `statusUrl` 을 그대로 폴링 — URL 하드코딩 금지.

### 상태 페이로드 (`TaskStatusResponse`)
```json
{
  "taskId": "...",
  "status": "queued|processing|completed|failed",  // 소문자
  "progress": 0,                                    // 0-100
  "message": "현재 단계 설명",
  "result": "...",                                  // 완료 시 JSON 문자열 또는 텍스트
  "createdAt": "2026-04-...",
  "updatedAt": "2026-04-..."
}
```

---

## 호출 패턴 (도메인 외부)

`AsyncTaskService` 는 `service/AsyncTaskService.java` (도메인 밖). 사용 위치:

| 호출자 | 용도 |
|---|---|
| `exam.controller.ExamGenerationController` | `POST /api/exams/generation/async` 진입점 — `createTask` 후 `@Async` 호출 |
| `exam.service.ExamGenerationService.generateExamAsync` | 진행률 갱신 + 완료 시 `result` JSON 저장 |
| `exam.service.ExamGradingService` | 채점 비동기 진행 상태 |
| `exam.controller.ExamSubmissionController` | (필요 시 채점 결과 폴링) |
| `material.generation.controller.MaterialGenerationController` | 5-Phase 생성 비동기 시작 |
| `material.generation.service.MaterialGenerationService.processPhase3To5Async` | Phase 별 진행률 갱신 |

---

## 상시 주의사항 (Gotcha)

### `@Async` 메서드 안에서 매 단계마다 `updateTaskStatus` 호출
프로젝트 컨벤션:
```java
asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 20, "Phase 1 시작...");
// ... 작업 ...
asyncTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 60, "Phase 3 진행 중...");
// ... 완료 ...
asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료", resultJson);
```
실패 시:
```java
catch (Exception e) {
    asyncTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, null, "오류 발생: " + e.getMessage());
}
```

### TTL 24h — 장기 작업 주의
Redis TTL 이 작업 시작 시 24h. **갱신 시마다 TTL 재설정** (`set ... ttl`) 이라 단계 갱신이 활발하면 자동 연장. 24h 동안 갱신 한 번도 없으면 만료. Phase 3-5 폴링은 최대 20분이라 안전.

### `status` 는 소문자 문자열
Enum `TaskStatus.PROCESSING` → JSON `"processing"`. FE 측이 대문자 비교하면 깨짐. `status.name().toLowerCase()` 변환은 `AsyncTaskService` 내부에서 처리.

### `result` 는 문자열 — JSON 이면 직렬화 1회 더
복합 결과 저장 시 호출자가 `objectMapper.writeValueAsString(...)` 으로 직접 직렬화한 문자열을 넘김:
```java
String resultJson = objectMapper.writeValueAsString(Map.of(
    "examSessionId", response.getExamSessionId(), ...
));
asyncTaskService.updateTaskStatus(taskId, TaskStatus.COMPLETED, 100, "완료", resultJson);
```
FE 는 받은 후 다시 `JSON.parse(result)` — 이중 직렬화 인지하고 사용.

### 작업 ID 충돌
UUID v4 라 사실상 충돌 없음. 호출자가 직접 발급해서 넘기는 구조라 ID 발급 패턴 변경 시 (예: 짧은 코드) 충돌 검사 필요.

### 권한·소유자 검증 없음
`getTaskStatus` 는 taskId 만 보면 누구나 조회. **민감 결과 저장 시 호출자가 result 에서 민감정보 제외** 또는 컨트롤러에 owner 검증 추가 필요.

### 트랜잭션 밖 호출 권장
Redis 호출은 DB 트랜잭션과 무관 — 트랜잭션 안에서 호출해도 동작하지만 의도와 분리되도록 트랜잭션 밖에서 부르는 게 깔끔.

---

## 주요 파일

### Controller
- `controller/AsyncTaskController.java` — `GET /api/tasks/{taskId}/status`

### Entity (Enum)
- `entity/TaskStatus.java` — QUEUED / PROCESSING / COMPLETED / FAILED

### DTO
- `dto/AsyncTaskResponse.java` — 비동기 시작 시 응답 (`taskId`, `status="accepted"`, `statusUrl`)
- `dto/TaskStatusResponse.java` — 상태 폴링 응답 페이로드

### 도메인 외부 (서비스 본체)
- `service/AsyncTaskService.java` — Redis 기반 상태 저장/조회. 본 도메인에 흡수하지 않은 이유: 다도메인(`exam`, `material`)이 동시 의존하는 공통 인프라 성격.

### `CommonErrorCode.TASK_NOT_FOUND`
없는 taskId 조회 시 404. 일반 예외와 동일하게 `GlobalExceptionHandler` 에서 처리.
