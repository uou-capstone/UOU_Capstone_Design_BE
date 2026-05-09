# CLAUDE.md

이 저장소에서 Claude Code가 작업할 때 참고할 프로젝트 규칙.

---

## 프로젝트 개요

- UOU Capstone — AI 기반 학습 플랫폼 백엔드
- 2-tier 구조: **Spring Boot (Java)** + **FastAPI (Python, 별도 브랜치)**
- 주 브랜치
  - `feat/v3-springboot` — Spring 작업 메인
  - `llm_multi_agent` — FastAPI 실제 Python 코드 (현재 브랜치에는 껍데기만 있음)

---

## 환경 / 실행

- OS: Windows 11
- Shell: Git Bash (Unix 문법, 예: `/dev/null`·forward slash 경로)
- Spring Boot 포트
  - **로컬 (개발)**: `8081` — `local` 프로필 (`application-local.yml`)
  - **배포 (prod)**: `8080` — `prod` 프로필 (기본 `application.yml` 상속)
- FastAPI: `8000`
- 로컬 의존: MySQL, Redis
- 로컬 실행:
  ```bash
  ./gradlew bootRun --args='--spring.profiles.active=local'
  ```

---

## SSE 응답 규약

- 반환: `Flux<ServerSentEvent<Map<String,Object>>>`
- `SecurityConfig`에 `DispatcherType.ASYNC`/`ERROR` permit 유지 (Tomcat async dispatch용)
- 클라이언트는 `fetch` + `ReadableStream` 권장 — `EventSource`는 Authorization 헤더 불가

---

## 금지 사항 (프로젝트 특화)

- prod에 `spring.jpa.show-sql: true` 또는 `org.hibernate.SQL: INFO` — PII 유출
- `@Transactional` 안에서 `WebClient.block()` — DB 커넥션 장기 점유. 외부 HTTP는 트랜잭션 **밖**으로 분리
- prod 에러 응답에 `ex.getStatusText()`·스택트레이스 등 내부 정보 노출 — 고정 메시지로 대체
- CORS origin 하드코딩 — `CORS_ALLOWED_ORIGINS` 환경변수 사용

---

## 관련 워크플로우 (Skill)

- 금지 패턴 자동 검사: `/spring-lint` (위 "금지 사항" 항목들 검사)
- 주제별 분리 커밋: `/commit-by-topic`
- DEV_NOTES 항목 추가: `/dev-notes-entry`

---

## 참조 포인터

| 문서 | 용도 |
|---|---|
| `uou-capstone/DEV_NOTES.md` | 버그·성능 수정 이력 (이번 작업이 어디에 속하는지 확인) |
| `uou-capstone/FRONTEND_V2_V3_API.md` | FE-BE API 계약 |
| `uou-capstone/V27_E2E_SMOKE_TEST.md` | E2E 스모크 시나리오 |
| `uou-capstone/scripts/smoke-v27.ps1` | 스모크 스크립트 (`pwsh ./scripts/smoke-v27.ps1 ...`) |

각 도메인 작업 전 해당 README를 먼저 확인 (외부 의존·Gotcha·주요 파일 정리됨).

| 도메인 | 핵심 |
|---|---|
| [course](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/course/README.md) | 강의실(Course) + 수강(Enrollment) — invitationCode 입장, 자식 정리 순서. `CourseAccessService` 권한 헬퍼 (notice/discussion/attendance 공유) |
| [course/lecture](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/course/lecture/README.md) | 강의 CRUD + v1 legacy AI 흐름 + AI 콜백 (secret 검증) |
| course/notice | 공지사항 + 댓글 (1단계 답글). 작성/수정 교사, 댓글 학생도 가능. 작성 시 ACTIVE 수강생 알림 발송 |
| course/discussion | 토론·자유게시판 + 댓글 (1단계 답글). 학생도 작성 가능. viewCount 증가, allowComments 토글 |
| course/attendance | 명시 출석 — 회차(lecture 매핑 OR 독립) + record 일괄. 회차 생성 시 ACTIVE 수강생 ABSENT 자동 |
| [material](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/material/README.md) | PDF 업로드·스트리밍 + AI 5-Phase 생성 (Redis Pub/Sub → SSE) |
| [exam](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/exam/README.md) | 5종 시험 생성·응시·채점·토론 (`@Async("taskExecutor")`, FastApiBridgeClient/SessionClient) |
| [learning](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/learning/README.md) | v3 학습 세션 — FastAPI MergeEduAgent 프록시 (NDJSON → SSE) |
| [user](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/user/README.md) | User/Teacher/Student + JWT/Refresh + OAuth(Kakao) |
| [assessment](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/assessment/README.md) | v1 정형 평가 컨테이너 (Assessment + ChoiceOption) |
| [submission](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/submission/README.md) | v1 답안 제출 (Submission + StudentAnswer) — 채점 흐름은 exam 도메인 |
| [task](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/task/README.md) | 비동기 작업 상태 추적 (Redis `sb:task:{id}`, TTL 24h) |
| [inquiry](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/inquiry/README.md) | 학생 답변 평가 — FastAPI `/api/v2/qa/evaluate` 호출 |

### 인프라 패키지 README

도메인 외부 cross-cutting 패키지.

| 패키지 | 핵심 |
|---|---|
| [integration/fastapi](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/integration/fastapi/README.md) | 5개 클라이언트 ↔ FastAPI 엔드포인트 매핑 + WebClient 2종 / NDJSON 처리 표준 |
| [agent](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/agent/README.md) | AI Agent 추상화 — `AbstractAgent` 재시도 정책 + material 8개 Agent / Phase 매핑 |
| [security](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/security/README.md) | JWT 필터·발급 + 카카오 OAuth2 + 401/403 응답 표준화 |
| [service](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/service/README.md) | cross-cutting 서비스 카탈로그 — `CurrentUserResolver`, Redis 키 네임스페이스(`sb:`) |
| [config](uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/config/README.md) | SecurityConfig·WebClientConfig·AsyncConfig·RedisConfig·인터셉터·GlobalExceptionHandler |