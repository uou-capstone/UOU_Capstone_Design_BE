# MergeEdu 에이전트 기능 5종 — FastAPI 측 협의 문서

이번 라운드에서 학생-선생 상호작용 기능(공지·토론·출석)은 Spring 측에서 단독 구현했지만,
**AI 에이전트 5종은 FastAPI 측과 합의가 필요**해서 본 문서로 인계.

- 작성: 2026-05-09
- Spring 측 PR: `feat/v3-springboot` (Notice/Discussion/Attendance 도메인 신규)
- 레퍼런스 레포: `https://github.com/uou-capstone/UOU_Capstone_Design_AI` (`new` 브랜치 `MergeEduAgentFull/`)
- 본 문서가 다루지 **않는 것**: 오케스트레이션 엔진, 도구 카탈로그, state 모델 — 레퍼런스 내부 아키텍처는 이식하지 않음.

---

## TL;DR

레퍼런스의 5개 AI 기능을 우리 v3 스택에 이식하려면 다음이 필요하다.

| 기능 | FastAPI 신규 엔드포인트 | Spring 신규 컨트롤러·도메인 |
|---|---|---|
| 1. Discussion AI Assistant | `/bridge/discussion_assistant_stream` (NDJSON) | `domain/course/discussion/assistant/` 신설 |
| 2. Exam Studio Chat | `/bridge/exam_studio/{pdf_context, chat_stream}` | `domain/exam/studio/` 신설 |
| 3. Student Report Chatbot | `/bridge/report/student_chat_stream` | 기존 `domain/course/report/` 확장 |
| 4. Report Criteria AI 추천 | `/bridge/report/criteria_assistant_stream` | 신규 `criteria` 엔티티 + assistant |
| 5. Classroom 종합 리포트 | `/bridge/report/classroom_analyze[_stream]` | 기존 `CourseReportController` 확장 |

각 엔트리 포인트에서 **FastAPI 합의 필요 항목**을 별도 명시.

---

## 공통 통합 패턴 (현 v3 와 동일)

레퍼런스의 모든 스트림은 NDJSON 이벤트. 우리는 다음 패턴으로 통합:

1. **FastAPI**: `/bridge/...` 엔드포인트 추가 (NDJSON 출력 — `thought_delta` / `answer_delta` / `done` / `error`).
2. **Spring `FastApiBridgeClient`**: 신규 메서드 추가 (`Flux<JsonNode>` 또는 `Flux<NdjsonEvent>` 반환).
3. **Spring 컨트롤러**: NDJSON → SSE 변환. 현 `LearningSessionController` (v3 학습 세션) 패턴 그대로:
   - 반환: `Flux<ServerSentEvent<Map<String,Object>>>`
   - `SecurityConfig` 의 `DispatcherType.ASYNC/ERROR` permit 유지
4. **인증**: `@PreAuthorize` 로 1차 게이트 + `CourseAccessService.loadCourseAsTeacher` / `loadCourseAsParticipant`로 강의실 권한 검증.

---

## 1. Discussion AI Assistant

### 레퍼런스
- `POST /:cid/discussions/assistant`
- `POST /:cid/discussions/assistant/stream`

### 핵심 동작
학생이 토론 게시글 작성 중 AI 가 제목·본문 초안을 제안. 강의 컨텍스트(과목명·이전 게시글) 와 학생이 입력한
키워드를 기반으로 글 작성 보조.

### 요청 / 응답 (참고 — 우리 측 변형 예상)
```jsonc
// Request
POST /api/courses/{courseId}/discussions/assistant/stream
{
  "topic": "이 단원에서 헷갈리는 부분 질문",
  "category": "QUESTION",
  "previousDraft": "..." // 선택, 부분 작성한 본문 이어쓰기
}

// SSE 이벤트 (NDJSON 변환):
event: thought_delta   data: { "text": "..." }
event: answer_delta    data: { "text": "..." }
event: done            data: { "title": "...", "contentMarkdown": "..." }
event: error           data: { "code": "AI_SERVER_ERROR" }
```

### FastAPI 측 신규 필요
- `/bridge/discussion_assistant_stream` (POST, NDJSON)
- 컨텍스트 입력: courseId, 최근 N개 discussion title/category, 학생 키워드
- Gemini 프롬프트: 토론 글 작성 보조용 (출력은 title + contentMarkdown 분리)

### Spring 측 신규 필요
- 도메인: `domain/course/discussion/assistant/`
  - `controller/DiscussionAssistantController` — `POST /api/courses/{cid}/discussions/assistant/stream`
  - `service/DiscussionAssistantService` — 컨텍스트 빌드 + FastApiBridgeClient 호출
- `FastApiBridgeClient.discussionAssistantStream(...)` 메서드 추가

### 의존
- 우리 측 Discussion 도메인 (이미 구현 — `domain/course/discussion/`)
- `CourseAccessService.loadCourseAsParticipant` (이미 구현)

### 미정 항목 — FastAPI 팀과 합의 필요
- 컨텍스트로 보낼 이전 게시글 개수 (3개? 10개?)
- 카테고리(QUESTION/FREE/RESOURCE) 별 프롬프트 분기 여부
- 동기 엔드포인트(`/assistant`) 도 둘지, stream 만 지원할지

---

## 2. Exam Studio Chat

### 레퍼런스
- `POST /weeks/:id/exam-studio/pdf-context`  — PDF 텍스트 추출 + 컨텍스트 생성
- `POST /weeks/:id/exam-studio/chat`         — AI 채팅 (단발성)
- `POST /weeks/:id/exam-studio/chat/stream`  — AI 채팅 (스트림)

### 핵심 동작
교사가 강의 자료 PDF 를 컨텍스트로 두고 AI 와 대화하며 시험 문항을 작성. 우리 기존 ExamGeneration 은
"한 번에 자동 생성"이지만, exam-studio 는 **대화형 보조** — 교사가 문항을 다듬을 수 있음.

### 우리 경로 변환
레퍼런스의 `/weeks/:id/...` → 우리는 weeks 미도입 → **`/api/courses/{courseId}/exam-studio/...`** 로 변환.
또는 `lectureId` 단위로 — `/api/lectures/{lectureId}/exam-studio/...`. 상세는 결정 필요.

### 요청 / 응답
```jsonc
// 1) PDF 컨텍스트 등록
POST /api/courses/{cid}/exam-studio/pdf-context
{ "materialId": 123 }   // 우리 Material 엔티티 재사용
→ { "contextId": "abc-123", "pageCount": 42 }

// 2) 대화 (스트림)
POST /api/courses/{cid}/exam-studio/chat/stream
{
  "contextId": "abc-123",
  "messages": [{ "role": "user", "content": "객관식 5문항 만들어줘" }]
}
→ NDJSON 스트림: thought_delta / answer_delta / done(questionDraft?) / error
```

### FastAPI 측 신규 필요
- `/bridge/exam_studio/pdf_context` — PDF 텍스트 추출 + 임베딩(또는 캐시 키 발급)
- `/bridge/exam_studio/chat_stream` — Gemini 대화 보조

### Spring 측 신규 필요
- 도메인: `domain/exam/studio/`
- 컨트롤러 위치는 결정 필요 (course 기반 vs lecture 기반)
- 기존 Material PDF 인프라 재사용 (`material/service/MaterialService.getFile(...)`)

### 의존
- Material 도메인 (이미 존재)
- 기존 `FastApiBridgeClient` 패턴

### 미정 항목 — FastAPI 팀과 합의 필요
- contextId 의 TTL / 캐시 정책 (PDF 텍스트 추출 비용 고려)
- 동기 chat 엔드포인트 필요한가
- 출력 형식: free-form 텍스트만 vs 구조화된 question draft (JSON)

---

## 3. Student Report Chatbot

### 레퍼런스
- `POST /:cid/report/students/:sid/chat/stream`

### 핵심 동작
교사가 특정 학생의 리포트(이미 생성된 데이터) 를 보면서 follow-up 질문을 채팅으로 한다. 답변은 학생의
학습 기록·퀴즈 결과·feedback 을 컨텍스트로 한다.

### 우리 경로
기존 `CourseReportController` (`/api/courses/{courseId}/reports/...`) 에 추가:
- `POST /api/courses/{courseId}/reports/students/{studentId}/chat/stream`

### 요청 / 응답
```jsonc
POST /api/courses/{cid}/reports/students/{sid}/chat/stream
{
  "messages": [{ "role": "user", "content": "이 학생이 약한 단원이 어디야?" }]
}
→ NDJSON: thought_delta / answer_delta / done / error
```

### FastAPI 측 신규 필요
- `/bridge/report/student_chat_stream`
- 입력: 학생 리포트 JSON + 메시지
- 출력: NDJSON 대화 스트림

### Spring 측 신규 필요
- `CourseReportController` 에 메서드 추가
- `CourseReportService` 에 chat 메서드 + `FastApiBridgeClient.reportStudentChatStream(...)` 추가

### 의존
- 기존 student report 데이터 (`domain/course/report/`)
- 기존 학습 세션 로그 / 퀴즈 결과 (`domain/learning/`, `domain/exam/`)

### 미정 항목 — FastAPI 팀과 합의 필요
- 컨텍스트로 넣을 학습 로그 범위 (최근 N건? 전체?)
- 다중 턴 대화 — 우리 측이 history 유지할지, FastAPI 가 stateful 할지
- 학생 리포트 JSON 스키마 (현 `CourseReportController` 의 응답 구조 그대로 보낼 것인지)

---

## 4. Report Criteria AI 추천

### 레퍼런스
- `GET /:cid/report/criteria` — 목록
- `POST /:cid/report/criteria` — 추가
- `PATCH /:cid/report/criteria/:criterionId` — 수정
- `DELETE /:cid/report/criteria/:criterionId` — 삭제
- `POST /:cid/report/criteria/assistant/stream` — AI 추천

### 핵심 동작
교사가 강의실 평가 기준(criterion: label, description, weight 등) 을 설정. AI 는 강의 컨텍스트를 보고
적절한 기준을 추천.

### 우리 경로
- CRUD: `/api/courses/{courseId}/reports/criteria/...`
- AI: `POST /api/courses/{courseId}/reports/criteria/assistant/stream`

### 데이터 모델 (Spring 신규)
```java
// 신규 엔티티
public class CourseReportCriterion extends BaseTimeEntity {
    private Long id;
    private Course course;       // FK
    private String label;        // "개념 이해도"
    private String description;  // "강의 핵심 개념을 얼마나 정확히 설명할 수 있는가"
    private int weight;          // 0–100
}
```
신규 V3 마이그레이션 (V2 다음): `course_report_criteria` 테이블.

### FastAPI 측 신규 필요
- `/bridge/report/criteria_assistant_stream`
- 입력: courseId, 강의명, 기존 기준 목록
- 출력: NDJSON — `criterion_suggestion` 이벤트 (label, description, weight) 다회 + done

### Spring 측 신규 필요
- 신규 도메인: `domain/course/report/criteria/`
- 엔티티 / repository / service / controller (CRUD + 추천 stream)
- AI 추천만 별도 컨트롤러 둘지, 같은 컨트롤러에 메서드로 둘지 결정 필요

### 의존
- 기존 `domain/course/report/` (이미 student 리포트는 있음)

### 미정 항목 — FastAPI 팀과 합의 필요
- weight 범위 정의 (0–100? 1–5?)
- 추천 개수 (3건 기본? 사용자 지정?)
- 한국어 출력 강제 여부

---

## 5. Classroom 종합 리포트

### 레퍼런스
- `GET /:cid/report` — 강의실 단위 종합 리포트 조회
- `POST /:cid/report/analyze` — 리포트 생성 (동기)
- `POST /:cid/report/analyze/stream` — 리포트 생성 (스트림)

### 핵심 동작
학생별 리포트 위에 강의실 전체 통계·하이라이트(전반적 강세/약세, 분포, 코칭 우선순위) 레이어를 둠.
교사가 클래스룸 전체를 한눈에 볼 수 있도록.

### 우리 경로
기존 `CourseReportController` 확장:
- `GET /api/courses/{courseId}/reports/classroom`
- `POST /api/courses/{courseId}/reports/classroom/analyze[/stream]`

### 데이터 모델
신규 엔티티: `ClassroomReport (id, course, summaryMarkdown, generatedAt, ...)` — JSON 또는 Markdown
저장. 한 강의실당 1건 (UPSERT).

### FastAPI 측 신규 필요
- `/bridge/report/classroom_analyze[_stream]`
- 입력: 모든 학생 리포트 + 강의 정보 + (선택) criteria
- 출력: 강의실 종합 인사이트 (Markdown + 구조화된 통계 JSON)

### Spring 측 신규 필요
- `CourseReportController` 에 메서드 추가
- `ClassroomReportService` (신규) — 학생 리포트 집계 + FastApiBridgeClient 호출
- 신규 V3 또는 V4 마이그레이션 (criteria 와 묶어서 또는 분리)

### 의존
- 학생별 리포트 (이미 존재)
- (선택) Criteria — 4번 항목과 묶일 수 있음

### 미정 항목 — FastAPI 팀과 합의 필요
- 리포트 캐싱 정책 (생성 시점 기준 N시간 유효? 새 학생 추가 시 invalidate?)
- 출력 형식: Markdown only vs 구조화된 JSON + Markdown
- 학생 수가 많을 때(예: 100명+) 처리 방법 (배치 분할?)

---

## 다음 단계

1. **FastAPI 측 검토**: 위 5개 `/bridge/...` 엔드포인트 명세 확정 — 입력/출력 스키마, NDJSON 이벤트 타입.
2. **공통 결정**: contextId TTL, 캐싱 정책, 다중 턴 history 보관 위치, 한국어 출력 강제 여부.
3. **우선순위 결정**: 5종 동시 도입 vs 단계별. 추천 순서 — Student Report Chatbot(3) → Exam Studio(2) → Discussion Assistant(1) → Criteria(4) → Classroom Report(5).
4. **각 기능별 별도 PR**: Spring 측은 위 합의가 끝나면 도메인별로 PR 분리해서 진행.

---

## 참고

- 본 문서는 **FastAPI 합의가 끝날 때까지 코드 변경 없음**.
- 합의 완료 후 각 기능별 신규 도메인을 추가할 때 본 문서의 경로·엔드포인트를 그대로 사용.
- 기존 `domain/learning/` (v3 학습 세션) 의 NDJSON → SSE 변환 코드를 패턴으로 재사용.
