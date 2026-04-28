# course/lecture 도메인

`Course` 하위의 주차별 강의(`Lecture`) 와 강의 단위 AI 콘텐츠(`GeneratedContent`) 관리.
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **Lecture 엔티티**: `course` (FK), `title`, `weekNumber`, `description`, `aiGeneratedStatus` — `materials` / `generatedContents` / `studentInquiries` 자식 컬렉션 보유 (`cascade=ALL`, `orphanRemoval=true`)
- **GeneratedContent 엔티티**: 강의별 AI 생성물 단일 행. `contentType` (SCRIPT/SUMMARY/VISUAL_AID), `contentData` (JSON 문자열 — TEXT), `materialReferences`, 선택적 `student`/`session`/`aiQuestionId`
- **AiGeneratedStatus**: `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`
- 역할: `TEACHER` 가 생성·수정·삭제, `STUDENT` 는 상세 조회·스트리밍 소비

---

## 주요 플로우

### 강의 CRUD (`LectureController`)
- `POST /api/courses/{courseId}/lectures` (TEACHER) — 강의 생성. `aiGeneratedStatus=PENDING` 기본값
- `GET  /api/lectures/{lectureId}` (TEACHER/STUDENT) — 상세 + GeneratedContent 목록
- `PUT  /api/lectures/{lectureId}` (TEACHER) — 정보 수정
- `DELETE /api/lectures/{lectureId}` (TEACHER) — 강의 삭제. 자식 테이블 정리 순서가 중요 (아래 Gotcha)

### v1 Legacy AI 흐름 (`LegacyLectureFlowController` / `LegacyLectureFlowService`)
> ⚠️ **유지보수 전용**. 신규 기능은 이 경로에 추가하지 말고 v3 learning session 또는 v2 단건 API 로 분리.

- `POST /api/lectures/{lectureId}/generate-content` (TEACHER) — FastAPI delegator 비동기 호출
- `GET  /api/lectures/{lectureId}/ai-status` — 생성 상태 폴링
- `POST /api/lectures/{lectureId}/stream/initialize` — 스트리밍 세션 초기화 (PDF 분석)
- `GET|POST /api/lectures/{lectureId}/stream/next` (SSE) — FastAPI NDJSON → SSE 이벤트 중계
- `GET  /api/lectures/{lectureId}/stream/session` — 현재 스트리밍 세션 조회
- `POST /api/lectures/{lectureId}/stream/answer` — AI 질문에 대한 사용자 답변
- `POST /api/lectures/{lectureId}/stream/cancel` — 세션 취소

### AI 콜백 (`AiCallbackController`)
- `POST /api/ai/callback/lectures/{lectureId}` — ai-service 가 생성 완료 시 호출
- 인증: `X-AI-SECRET-KEY` 헤더, `MessageDigest.isEqual` 로 **상수시간 비교** (타이밍 공격 방지)
- 보호되지 않은 엔드포인트 (`@PreAuthorize` 없음) — secret 검증이 유일한 방어선

---

## 외부 의존

- **FastAPI delegator** (legacy 흐름)
  - 클라이언트: `integration/fastapi/FastApiDelegatorClient`
  - NDJSON 스트림: `streamLectureContent(payload, secretKey)` → `LectureStreamChunk` (THOUGHT/CONTENT/DONE/ERROR)
- **AI secret key**: `${ai.service.secret-key}` — 콜백·delegator 양쪽에서 사용

---

## SSE 이벤트 형식 (`/stream/next`)

| event | data 예시 |
|---|---|
| `thought` | `{"type":"thought_delta","contentType":"THOUGHT","delta":"..."}` |
| `message` | `{"type":"delta","delta":"본문 조각"}` |
| `done` | `{"type":"done","lectureId":N,"hasMore":false,"waitingForAnswer":false}` |
| `done` | `{"type":"done","status":"WAITING_FOR_ANSWER","waitingForAnswer":true,...}` |
| `error` | `{"type":"error","message":"..."}` |

응답 헤더: `X-Accel-Buffering: no`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` — nginx/프록시 버퍼링 차단.

---

## 상시 주의사항 (Gotcha)

### N+1 방지 — `findByIdWithCourse` 우선
```java
@Query("SELECT l FROM Lecture l JOIN FETCH l.course c JOIN FETCH c.teacher WHERE l.id = :id")
Optional<Lecture> findByIdWithCourse(@Param("id") Long id);
```
권한 체크에 `lecture.getCourse().getTeacher()` 가 필요하면 반드시 이 메서드 사용. `findById` 는 lazy 3회 호출.
**현재 `LectureService.getLectureDetail/updateLecture/deleteLecture` 는 `findById` 를 쓰고 있어 추가 SELECT 가 발생** — 후속 정리 대상.

### 강의 삭제 시 자식 정리 순서
FK 제약 위반을 피하려면 정확히 이 순서:
```java
generatedContentRepository.clearSessionByLectureId(lectureId);   // GeneratedContent.session_id NULL 처리
generationSessionRepository.deleteByLectureId(lectureId);
examSessionRepository.deleteByLectureId(lectureId);
examProfileRepository.deleteByLectureId(lectureId);
materialRepository.deleteByLectureId(lectureId);
lectureRepository.delete(lecture);                                // cascade: generated_contents, student_inquiries
```
- `GenerationSession` 은 `generated_content.session_id` FK 로 참조되므로 **세션 삭제 전 참조 해제 필수**
- `clearSessionByLectureId` 는 네이티브 UPDATE — JPQL 로 SET 컬럼 모호성 회피

### AI 콜백 secret 비교
```java
// ✅ 표준 — 타이밍 공격 방지
MessageDigest.isEqual(aBytes, bBytes);

// ❌ 금지
return secretKeyHeader.equals(aiServiceSecretKey);   // 길이/문자별 시간차 노출
```

### Legacy 흐름 격리
`LegacyLectureFlowService` 는 `@Deprecated` 마킹은 안 됐지만 컨트롤러에 "신규 기능 추가 금지" 명시. 새 AI 기능 구현은:
- 단일 페이지 응답 → `material` 도메인 generation 5-Phase
- 실시간 학습 세션 → `learning` 도메인 v3 session

### GeneratedContent.contentData 는 JSON 문자열
`@Lob` `TEXT` 컬럼이지만 실제 내용은 JSON 직렬화된 문자열. 읽을 때 `ObjectMapper.readValue` 필요. 스키마 변경 시 마이그레이션 X — 필드 추가는 nullable 로만.

### 권한 체크 (`validateLectureParticipant`)
- TEACHER: `course.getTeacher().getId()` 일치 여부
- STUDENT: `enrollmentRepository.existsByStudentAndCourse(...)` 결과
- 둘 다 아니면 `FORBIDDEN`. `currentUserResolver.getUser()` 1회 호출만 사용.

---

## 주요 파일

### Controller
- `controller/LectureController.java` — 강의 CRUD
- `controller/LegacyLectureFlowController.java` — v1 legacy AI 생성·스트리밍 (유지보수 전용)
- `controller/AiCallbackController.java` — ai-service 콜백 (secret 검증)

### Service
- `service/LectureService.java` — 강의 CRUD + 자식 정리 (137줄)
- `service/LegacyLectureFlowService.java` — legacy 생성/스트리밍 (348줄)

### Repository
- `repository/LectureRepository.java` — `findByIdWithCourse` (JOIN FETCH)
- `repository/GeneratedContentRepository.java` — `findByLectureId`, `findByAiQuestionId`, `clearSessionBy(Course|Lecture)Id` (네이티브 UPDATE)

### Entity
- `entity/Lecture.java`
- `entity/GeneratedContent.java` — SCRIPT/SUMMARY/VISUAL_AID
- `entity/AiGeneratedStatus.java` — PENDING/PROCESSING/COMPLETED/FAILED
- `entity/ContentType.java`

### DTO
- `dto/LectureCreateRequestDto.java`, `LectureUpdateRequestDto.java`, `LectureResponseDto.java`, `LectureDetailResponseDto.java`
- `dto/Streaming*Dto.java` (Initialize/Session/Answer/Content/Chapter) — legacy 스트리밍 응답
- `dto/AiResponseDto.java`, `AiContentGenerateRequestDto.java`, `AiQuestionAnswerRequestDto.java` — ai-service 인터페이스

### Exception
- `exception/StreamingApiException.java` — FastAPI 상태 매핑

### 외부 통신
- `integration/fastapi/FastApiDelegatorClient.java` — legacy 흐름 NDJSON 스트림
- `integration/fastapi/LectureStreamChunk.java` — SSE 이벤트 디코드 record
