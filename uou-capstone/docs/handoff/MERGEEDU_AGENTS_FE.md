# MergeEdu AI 에이전트 5종 — 프론트 인계 문서

이번 라운드에서 **MergeEdu AI 에이전트 5종** 이 Spring 에 연결되었다. FE 가 호출해야 하는 Spring API 와 SSE 스트림 처리 방법만 다룬다.

- 작성: 2026-05-11
- 백엔드 PR: `feat/v3-springboot` (커밋 `0aa0092` ~ `082c8ca`, 7건)
- FastAPI 측 계약: `ai-service/docs/BRIDGE_AGENT_ENDPOINTS.md` (`feat/refactor` 브랜치)
- 백엔드 메인 API 문서: `uou-capstone/FRONTEND_V2_V3_API.md`

---

## TL;DR (6줄)

1. **신규** — Discussion AI Assistant: 토론 게시글 초안 보조 (`/api/courses/{cid}/discussions/assistant/stream`). 학생·교사 모두 가능.
2. **신규** — Exam Studio: 교사 대화형 시험 작성 (`/api/courses/{cid}/exam-studio/{pdf-context, chat/stream}`). PDF context 발급 → SSE chat.
3. **신규** — Report Criteria: 평가 기준 CRUD + AI 추천 (`/api/courses/{cid}/reports/criteria[...]`). 교사 전용.
4. **신규** — Classroom Report: 강의실 종합 리포트 분석 (`/api/courses/{cid}/reports/classroom[...]`). 동기 + 스트림, 1 course = 1 row UPSERT.
5. **신규** — Student Report Chatbot: 교사가 학생 리포트로 follow-up 질문 (`/api/courses/{cid}/reports/students/{sid}/chat/stream`). 대화 이력 미저장 — FE 가 `messages[]` 매 요청 전달.
6. **공통** — 모든 스트리밍 응답은 SSE (`text/event-stream`). NDJSON 이벤트 타입(`thought_delta` / `answer_delta` / `criterion_suggestion` / `done` / `error` / `heartbeat`)이 그대로 SSE `event` 이름이 된다.

---

## 0. 공통 컨벤션

### 0-1. SSE 스트림 처리 — `EventSource` 못 씀

`EventSource` API 는 Authorization 헤더를 붙일 수 없어 사용 불가. **`fetch` + `ReadableStream`** 패턴 권장.

```ts
const res = await fetch('/api/courses/123/discussions/assistant/stream', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${accessToken}`,
    'Accept': 'text/event-stream',
  },
  body: JSON.stringify({ topic: '...', category: 'QUESTION' }),
});

const reader = res.body!.getReader();
const decoder = new TextDecoder();
let buffer = '';

while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });

  // SSE 메시지 단위 (빈 줄로 구분)
  let sep;
  while ((sep = buffer.indexOf('\n\n')) >= 0) {
    const raw = buffer.slice(0, sep);
    buffer = buffer.slice(sep + 2);

    // event:<name>\ndata:<json>
    const event = raw.match(/^event:\s*(.+)$/m)?.[1] ?? 'message';
    const data  = raw.match(/^data:\s*(.+)$/m)?.[1];
    if (!data) continue;
    const payload = JSON.parse(data);

    handleEvent(event, payload);
  }
}
```

### 0-2. SSE 이벤트 종류 (NDJSON `type` 그대로 매핑)

모든 5종 SSE 응답에서 동일하게 사용. event 이름별 처리:

| event 이름 | 의미 | FE 처리 |
|---|---|---|
| `thought_delta` | AI 가 "생각 중" 단계 텍스트 청크 | 토글 UI 등에 누적 표시. 사용자 답변 영역은 아님 |
| `answer_delta` | 실제 답변 본문 텍스트 청크 | 답변 UI 에 누적 표시 |
| `criterion_suggestion` | (Criteria Assistant 전용) 기준 1건 추천 — `data.label`, `data.description`, `data.weight` | 추천 카드 1장 추가 |
| `done` | 정상 종료 + 결과 데이터 (`data.data`) | 스트림 종료 신호, 결과 저장 후 UI 확정 |
| `error` | 오류 — `code`, `message` 포함 | 사용자에게 메시지 표시, 스트림 종료 |
| `heartbeat` | 연결 유지용 keep-alive | 무시 (UI 변화 없음) |
| `timeout` | 서버 측 idle 종료 신호 (180초) | 재접속 또는 사용자 안내 |

### 0-3. payload 의 공통 fallback 필드

`done.data` 또는 sync JSON 응답에 다음 필드가 포함될 수 있다 — Gemini quota / 오류 상황에서 deterministic fallback 결과를 반환할 때 구분용:

```json
{
  "source": "AI" | "FALLBACK",
  "fallbackUsed": false | true,
  "reason": null | "ai_fallback:quota_exceeded" | ...,
  "confidence": "LOW" | "MEDIUM" | "HIGH",
  "warnings": [ /* 있을 수 있음 */ ]
}
```

**FE 표시 권장**:
- `fallbackUsed === true` 또는 `source === "FALLBACK"` 시 "AI fallback 결과" 라벨 또는 톤다운 표시.
- `confidence === "LOW"` 시 "참고용 결과" 정도 표시.

### 0-4. 에러 응답

#### SSE `event: error`
```json
{ "type": "error", "code": "AI_SERVER_ERROR", "message": "AI 서비스 호출에 실패했습니다." }
```
- `code` / `message` 만 사용. 내부 stack trace / exception class 는 서버 측에서 strip 됨 (보안).

#### REST 에러 (`BusinessException`)
```json
{ "code": "4030", "message": "접근 권한이 없습니다." }
```
본 라운드 자주 만날 코드:
- `4030 FORBIDDEN` — 강의실 교사/참가자 아님
- `4040 RESOURCE_NOT_FOUND` — 학생/Material/Criterion 없음, 다른 강의실 ID 끼워넣기
- `4042 COURSE_NOT_FOUND` — 강의실 자체 없음
- `4000 INVALID_PARAMETER` — Material 이 PDF 아님 / weight 범위 / question·messages 둘 다 비어있음 등

### 0-5. 인증

모든 신규 endpoint 는 기존 JWT 토큰 그대로 사용. 별도 변경 없음.

---

## 1. Discussion AI Assistant

토론 게시글 초안 작성 보조. 학생·교사 모두 가능.

### Endpoint

```
POST /api/courses/{courseId}/discussions/assistant/stream
Accept: text/event-stream
Authorization: Bearer <token>
```

### Request body

```json
{
  "topic": "이 단원에서 헷갈리는 부분 질문",
  "category": "QUESTION",
  "previousDraft": "..."
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `topic` | string | ✅ | 최대 500자 |
| `category` | enum (`QUESTION` / `FREE` / `RESOURCE`) | optional | 미지정 시 AI 가 추정 |
| `previousDraft` | string | optional | 부분 작성한 본문 이어쓰기, 최대 10,000자 |

### SSE 응답 흐름

```
event: thought_delta   data: {"type":"thought_delta","text":"카테고리 분석 중..."}
event: thought_delta   data: {"type":"thought_delta","text":"..."}
event: answer_delta    data: {"type":"answer_delta","text":"## 질문"}
event: answer_delta    data: {"type":"answer_delta","text":"\n\n이 단원의..."}
event: done            data: {"type":"done","data":{ ... done.data 아래 ... }}
```

#### `done.data` 필드

```json
{
  "title": "이 단원에서 헷갈리는 부분 질문",
  "contentMarkdown": "## 질문\n\n...",
  "source": "AI",
  "fallbackUsed": false,
  "reason": null,
  "confidence": "MEDIUM",
  "warnings": []
}
```

FE 는 `title` 을 toggle UI 의 제목 input 에, `contentMarkdown` 을 본문 textarea 에 채워 넣는다 (사용자가 편집 후 일반 `POST /discussions` 로 작성).

### 권한
- 강의실 참가자 (교사 또는 ACTIVE 수강생).
- 4030 / 4042 발생 가능.

### 컨텍스트 (Spring 측 보강)
- 강의실명 + 최근 5개 게시글 title/category 가 자동 포함됨. FE 가 따로 보낼 필요 없음.

---

## 2. Exam Studio — PDF Context + Chat (교사 전용)

교사가 강의 자료 PDF 를 컨텍스트로 두고 AI 와 대화하며 시험 문항을 작성. 두 단계.

### 2-1. PDF Context 발급

```
POST /api/courses/{courseId}/exam-studio/pdf-context
Authorization: Bearer <token>
```

#### Request body
```json
{ "materialId": 123 }
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `materialId` | number | ✅ | 기존 Material 도메인의 PDF 자료 ID. `materialType === "PDF"` 만 허용 |

#### Response (200)
```json
{
  "contextId": "abc-123-xyz",
  "pageCount": 42,
  "charCount": 102_400,
  "expiresInSeconds": 3600,
  "cacheScope": "PROCESS_MEMORY",
  "bestEffort": true
}
```

**주의 — contextId 만료**: FastAPI process-memory 기반 best-effort cache 라 TTL/재시작/multi-worker 라우팅 변경 시 만료 가능. **2-2. chat/stream 호출 중 `error` 이벤트로 contextId 무효 응답을 받으면 다시 2-1 호출해서 새 contextId 발급 후 재시도**한다 (Spring 측 자동 retry 아님 — FE 책임).

#### 에러
- `4000 INVALID_PARAMETER` — Material 이 PDF 아님 / filePath 비어있음
- `4030 FORBIDDEN` — 강의실 교사 아님 / 타 강의실 materialId
- `4040 RESOURCE_NOT_FOUND` — materialId 없음

### 2-2. AI Chat (SSE)

```
POST /api/courses/{courseId}/exam-studio/chat/stream
Accept: text/event-stream
Authorization: Bearer <token>
```

#### Request body
```json
{
  "contextId": "abc-123-xyz",
  "messages": [
    { "role": "user", "content": "객관식 5문항 만들어줘" },
    { "role": "assistant", "content": "..." },
    { "role": "user", "content": "2번 문제 난이도 낮춰줘" }
  ],
  "message": null,
  "currentDraft": { /* 현재 편집 중인 시험 초안 free schema */ },
  "currentKstIso": "2026-05-11T10:00:00+09:00",
  "timeZone": "Asia/Seoul",
  "sourceText": "...추가 자료 텍스트...",
  "model": null
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `contextId` | string | ✅ | 2-1 응답값 |
| `messages` | `{role, content}[]` | optional | 대화 이력. 비울 때는 `message` 사용 |
| `message` | string | optional | 단발 메시지. `messages` 동시 사용 시 messages 우선 |
| `currentDraft` | object | optional | 현재 편집 중인 시험 초안. operations 가 patch 대상으로 참조 |
| `currentKstIso` / `timeZone` | string | optional | 시간 컨텍스트 |
| `sourceText` | string | optional | PDF 외 추가 자료 |
| `model` | string | optional | 모델 명시 (null = 기본) |

#### SSE 응답

이벤트는 표준 (`thought_delta`, `answer_delta`, `done`, `error`).

#### `done.data` 필드

```json
{
  "answerMarkdown": "...",
  "operations": [
    { "type": "patchExamSettings", "patch": { /* ... */ } },
    { "type": "appendQuestions",   "questions": [ /* ... */ ] },
    { "type": "replaceQuestion",   "index": 2, "question": { /* ... */ } }
  ],
  "source": "AI",
  "fallbackUsed": false,
  "reason": null,
  "confidence": "MEDIUM",
  "warnings": []
}
```

**operation 종류 (3개)**:
- `patchExamSettings` — 시험 설정(제목·시간·문항수 등) patch
- `appendQuestions` — 새 문항 N개 추가
- `replaceQuestion` — index 위치 문항 교체

FE 는 `operations` 배열을 순서대로 `currentDraft` 에 적용한다.

### 권한
- 교사 (`hasAuthority('TEACHER')`). 학생 403.

---

## 3. Report Criteria — CRUD + AI 추천 (교사 전용)

강의실 단위 평가 기준 (`label`, `description`, `weight`) 관리. AI 가 강의 컨텍스트 보고 자동 추천.

### 3-1. 목록
```
GET /api/courses/{courseId}/reports/criteria
```

#### Response
```json
[
  { "id": 1, "label": "개념 이해도", "description": "...", "weight": 40 },
  { "id": 2, "label": "응용력",     "description": "...", "weight": 30 }
]
```
(PageResponse 아님 — 강의실당 보통 10개 이하라 단순 배열)

### 3-2. 추가
```
POST /api/courses/{courseId}/reports/criteria
```

```json
{ "label": "개념 이해도", "description": "...", "weight": 40 }
```

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| `label` | string | ✅ | 1-100자 |
| `description` | string | optional | 최대 500자 |
| `weight` | int | ✅ | 0-100 |

**Response 201**: 생성된 `CriterionResponse` (id 포함).

### 3-3. 수정 (PATCH 부분 갱신)
```
PATCH /api/courses/{courseId}/reports/criteria/{criterionId}
```

```json
{ "weight": 50 }   // null 또는 미포함 필드는 변경 안 함
```

### 3-4. 삭제
```
DELETE /api/courses/{courseId}/reports/criteria/{criterionId}
→ 204 No Content
```

### 3-5. AI 추천 (SSE 스트림)

```
POST /api/courses/{courseId}/reports/criteria/assistant/stream
Accept: text/event-stream
```

#### Request body (모두 optional)

```json
{ "desiredCount": 3, "language": "ko" }
```

- `desiredCount` 기본 3, 범위 1-10
- `language` 기본 `"ko"`

#### SSE 응답 흐름

**중간 이벤트** `criterion_suggestion` 이 추천 1건씩 emit 된다:

```
event: thought_delta         data: {"type":"thought_delta","text":"강의 분석 중..."}
event: criterion_suggestion  data: {"type":"criterion_suggestion","data":{"label":"개념 이해도","description":"...","weight":40,"source":"AI","fallbackUsed":false,"reason":null,"confidence":"MEDIUM"}}
event: criterion_suggestion  data: {"type":"criterion_suggestion","data":{"label":"응용력","description":"...","weight":35,...}}
event: criterion_suggestion  data: {...}
event: done                  data: {"type":"done","data":{"suggestions":[/* 위 3건 그대로 */],"fallbackUsed":false}}
```

FE 는 `criterion_suggestion` 이벤트마다 카드 1장씩 추가해 보여주고, 사용자가 "추가" 클릭 시 3-2 (`POST /criteria`) 호출.

### 권한
- 교사 (`hasAuthority('TEACHER')`). 모든 endpoint.

---

## 4. Classroom Report — 강의실 종합 분석 (교사 전용)

학생별 리포트 위에 강의실 전체 통계·하이라이트 레이어. 1 course = 1 row UPSERT.

### 4-1. 조회

```
GET /api/courses/{courseId}/reports/classroom
```

#### Response
- `200` — 저장된 분석 결과 1건
- `204 No Content` — 아직 한 번도 analyze 안 함

```json
{
  "courseId": 123,
  "summaryMarkdown": "## 전반 요약\n...",
  "highlights": [ /* 자유 구조 */ ],
  "risks": [ /* 자유 구조 */ ],
  "coachingPriorities": [ /* 자유 구조 */ ],
  "source": "AI",
  "fallbackUsed": false,
  "reason": null,
  "confidence": "MEDIUM",
  "generatedAt": "2026-05-11T10:30:00"
}
```

### 4-2. 분석 (동기)

```
POST /api/courses/{courseId}/reports/classroom/analyze
```

Body 없음.

Response: FastAPI `done.data` 그대로 + Spring 측 UPSERT 자동.

```json
{
  "courseId": 123,
  "summaryMarkdown": "...",
  "highlights": [...],
  "risks": [...],
  "coachingPriorities": [...],
  "source": "AI",
  "fallbackUsed": false,
  ...
}
```

**처리 시간 주의**: 학생 수에 따라 30초~3분 소요 가능. FE 는 로딩 UI 표시 + 응답 대기. 타임아웃은 백엔드 600초.

### 4-3. 분석 (SSE 스트림 — 권장)

```
POST /api/courses/{courseId}/reports/classroom/analyze/stream
Accept: text/event-stream
```

Body 없음.

표준 SSE 이벤트 흐름. `done.data` 가 4-1 응답과 동일 형태.

**Spring 측 자동 처리**: `done` 이벤트 수신 시 Spring 이 자동으로 `classroom_reports` UPSERT. FE 는 별도 저장 호출 불필요.

### 권한
- 교사. 4030 발생 가능.

### 한계 (현재 MVP)
- 학생 수 1000명 초과 강의실은 일부만 분석에 포함 (백엔드 경고 로그). 후속 PR 에서 배치 분할 예정.
- 새 학생 추가 시 자동 invalidate 안 함 — 교사가 다시 `analyze` 호출해야 갱신.

---

## 5. Student Report Chatbot (교사 전용)

교사가 특정 학생의 리포트 화면에서 follow-up 질문. 대화 이력은 **저장 안 함** — FE 가 `messages[]` 매 요청 그대로 보냄 (stateless).

### Endpoint

```
POST /api/courses/{courseId}/reports/students/{studentId}/chat/stream
Accept: text/event-stream
Authorization: Bearer <token>
```

### Request body

```json
{
  "question": "이 학생에게 다음 주에 어떤 보충 학습을 주면 좋을까?",
  "messages": null,
  "model": null
}
```

또는 대화 이어가기:

```json
{
  "messages": [
    { "role": "user", "content": "이 학생이 약한 단원이 어디야?" },
    { "role": "assistant", "content": "..." },
    { "role": "user", "content": "그럼 어떤 자료를 주면 좋아?" }
  ],
  "model": null
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `question` | string | △ | 단발 메시지, 최대 4000자 |
| `messages` | `{role, content}[]` | △ | 대화 이력, 최대 30턴 |
| `model` | string | optional | 모델 명시 (null = 기본) |

**`question` 과 `messages` 중 하나는 반드시 있어야 함**. 둘 다 비어있으면 `4000 INVALID_PARAMETER`.

### SSE 응답

표준 이벤트 (`thought_delta`, `answer_delta`, `done`, `error`).

#### `done.data` 필드

```json
{
  "answer": "이 학생은 단원 3 ('함수') 에서 약점이 보입니다. 다음을 추천합니다: ...",
  "source": "AI",
  "fallbackUsed": false,
  "reason": null,
  "confidence": "MEDIUM"
}
```

FE 는 `answer_delta` 누적으로 실시간 표시 → `done.data.answer` 가 최종 확정값.

### 권한
- 교사 (`hasAuthority('TEACHER')`). 4030 발생 가능.
- 다른 강의실 / 다른 학생 ID 끼워넣기 → 4030 또는 4040.

### Spring 측 자동 처리
- 학생 분석 context (`StudentAiReportContextResponse`) + 학생 상세 리포트 (`StudentReportDetailResponse`) 가 FastAPI 에 자동 포함됨. FE 가 따로 보낼 필요 없음.

---

## 6. 빠른 참조

### 6-1. 모든 endpoint 한 줄 요약

| 기능 | Method + Path | 응답 | 권한 |
|---|---|---|---|
| Discussion Assistant | `POST /courses/{cid}/discussions/assistant/stream` | SSE | 참가자 |
| Exam Studio PDF Context | `POST /courses/{cid}/exam-studio/pdf-context` | JSON | 교사 |
| Exam Studio Chat | `POST /courses/{cid}/exam-studio/chat/stream` | SSE | 교사 |
| Criteria CRUD | `GET/POST/PATCH/DELETE /courses/{cid}/reports/criteria[/{id}]` | JSON | 교사 |
| Criteria AI 추천 | `POST /courses/{cid}/reports/criteria/assistant/stream` | SSE | 교사 |
| Classroom Report 조회 | `GET /courses/{cid}/reports/classroom` | JSON (200) / 204 | 교사 |
| Classroom Report 분석 (동기) | `POST /courses/{cid}/reports/classroom/analyze` | JSON | 교사 |
| Classroom Report 분석 (스트림) | `POST /courses/{cid}/reports/classroom/analyze/stream` | SSE | 교사 |
| Student Chatbot | `POST /courses/{cid}/reports/students/{sid}/chat/stream` | SSE | 교사 |

### 6-2. SSE 이벤트 처리 한 줄 요약

```ts
switch (event) {
  case 'thought_delta':       appendToThoughtUI(payload.text); break;
  case 'answer_delta':        appendToAnswerUI(payload.text); break;
  case 'criterion_suggestion': addSuggestionCard(payload.data); break;
  case 'done':                handleResult(payload.data); closeStream(); break;
  case 'error':               showError(payload.code, payload.message); closeStream(); break;
  case 'heartbeat':           /* ignore */ break;
  case 'timeout':             showReconnectPrompt(); closeStream(); break;
}
```

### 6-3. Fallback / 신뢰도 UI 패턴

| 조건 | 권장 표시 |
|---|---|
| `fallbackUsed === true` | "AI 일시 불안정 — fallback 결과" 배지 |
| `source === "FALLBACK"` | 위와 동일 |
| `confidence === "LOW"` | "참고용" 톤다운 텍스트 |
| `confidence === "HIGH"` | 별도 표시 안 함 (정상) |
| `warnings[]` 있음 | 각 경고 메시지 표시 |

---

## 7. 변경 영향 (기존 API 와의 관계)

- 기존 `/api/v3/session/*` 학습 세션 / `/api/v3/bridge/quiz` 퀴즈 / `/api/v3/bridge/grade` 채점 — **영향 없음**. 그대로 사용.
- 기존 `/api/courses/{cid}/reports/students[/{sid}]` (학생 리포트 조회) — **영향 없음**. 그대로 사용.
- 신규 5종은 모두 별도 path. 기존 호출 코드 변경 불필요.

---

## 8. 후속 / 미정 항목

다음은 현재 MVP 한계 — 동작에 영향 있으면 백엔드에 요청 바람:

1. **Exam Studio contextId 만료 자동 retry** — 현재는 FE 책임. 백엔드 자동 retry 1회 후속 PR.
2. **Classroom Report 100명+ 배치 분할** — 현재 1000명 한계.
3. **Student Chatbot 대화 이력 영속화** — 현재 stateless. 영속화 필요 시 별도 협의.
4. **Discussion Assistant `category` 분기 프롬프트** — FastAPI 측 합의 후 동작 변경 가능.
