# FE API 연동 가이드

> **최종 수정**: 2026-03-29 (코드 정합: §0 `pdf_path`, 세션 `payload` 키, `done.data.ui` widget/modal, Bridge 채점 길이 검증, v2 lecture-gen·헬스, `test-gen` Debate 400)  
> **대상**: 프론트엔드 개발자  
> **Base URL**: `http://{서버주소}`

---

## 목차

0. [공통 — `pdf_path` · 업로드](#0-공통--pdf_path--업로드)
1. [스트리밍 이벤트 공통 포맷](#1-스트리밍-이벤트-공통-포맷)
2. [v2 — 강의 설명 (단건)](#2-v2--강의-설명-단건)
3. [v2 — QA 평가](#3-v2--qa-평가)
4. [v2 — 시험 생성](#4-v2--시험-생성)
5. [v3 — 통합 세션 (오케스트레이션)](#5-v3--통합-세션-오케스트레이션)
6. [v3 — Bridge (단건 퀴즈/채점)](#6-v3--bridge-단건-퀴즈채점)
7. [공용 — PDF 분석 / 파일 업로드](#7-공용--pdf-분석--파일-업로드)
8. [스트리밍 처리 예시 코드](#8-스트리밍-처리-예시-코드)
9. [v2 — 강의 노트 생성 (요약)](#9-v2--강의-노트-생성-요약)

---

## 0. 공통 — `pdf_path` · 업로드

- **`pdf_path`**: 서버의 **`uploads/` 아래 실제 파일**만 허용 (경로 순회 `../` 거부 → `400`, 없으면 `404`). v2 강의/QA, PDF 분석, v3 세션 쿼리, Bridge 채점 등에 공통 적용.
- **권장**: `POST /api/files/upload` 응답의 **`path` 문자열을 그대로** 이후 API에 넣기 (아래 §7 참고).
- 이 문서 예시의 `/uploads/lecture.pdf` 는 **의미 설명용**이며, 실제로는 UUID 파일명 경로가 옵니다.

---

## 1. 스트리밍 이벤트 공통 포맷

스트리밍 응답은 모두 **NDJSON** (`application/x-ndjson`) 형식입니다.  
한 줄 = 독립적인 JSON 객체.

### 이벤트 타입별 필드

#### `agent_delta` — 텍스트 조각 (스트리밍 중)

```json
{
  "type": "agent_delta",
  "agent": "explainer",
  "tool": "EXPLAIN_PAGE",
  "channel": "thought",
  "delta": "강의 자료를 분석하는 중..."
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | `"agent_delta"` | 고정 |
| `agent` | string | `"explainer"` \| `"qa"` \| `"quiz"` \| `"grader"` |
| `tool` | string | `"EXPLAIN_PAGE"` \| `"ANSWER_QUESTION"` \| `"GENERATE_QUIZ"` \| `"GRADE"` |
| `channel` | `"thought"` \| `"main"` | **`"thought"`**: 사고 과정 → 토글 UI에 표시 후 자동 닫기<br>**`"main"`**: 실제 답변 → 메인 영역에 스트리밍 출력 |
| `delta` | string | 텍스트 조각 (누적해서 이어붙이기) |

> **스트리밍 출력 순서**:
> 1. `channel: "thought"` 이벤트들 → 토글 UI 안에 표시
> 2. thought 종료 → 토글 자동 닫기
> 3. `channel: "main"` 이벤트들 → 메인 채팅 영역에 순차 출력
> 4. `done` 이벤트 → 스트리밍 완료

---

#### `done` — 스트리밍 완료

```json
{
  "type": "done",
  "agent": "explainer",
  "tool": "EXPLAIN_PAGE",
  "final": true,
  "data": {}
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | `"done"` | 고정 |
| `final` | `true` | 고정 |
| `data` | object | 엔드포인트별 결과 데이터 (아래 각 섹션 참고) |

---

#### `error` — 오류 (스트림 종료)

```json
{
  "type": "error",
  "agent": "system",
  "message": "오류 내용",
  "data": {
    "type": "error",
    "code": "QUIZ_PROFILE_VALIDATION_FAILED",
    "message": "Invalid profile for quiz generation",
    "details": [
      {"field":"learning_goal","reason":"required"}
    ]
  }
}
```

> `error` 이벤트가 수신되면 스트림이 **반드시 종료**됩니다. 연결을 끊고 오류를 표시하세요.  
> `agent`는 `"system"` \| `"quiz"` \| `"grader"` \| `"explainer"` 등 호출 주체에 따라 달라질 수 있습니다.
>
> `data`는 선택 필드입니다. 있을 경우 `{code,message,details}` 형태의 **표준 오류 payload**로 UI에서 더 정교한 메시지/재시도 정책을 적용할 수 있습니다.

---

#### `heartbeat` — 연결 유지 (무시해도 됨)

```json
{"type": "heartbeat"}
```

> 10초마다 자동 전송됩니다. 클라이언트는 **무시하거나 연결 확인 용도로만 사용**하면 됩니다.

---

## 2. v2 — 강의 설명 (단건)

### 2-1. 비스트리밍 (전체 텍스트 한 번에)

```
POST /api/v2/lectures/generate
Content-Type: application/json
```

**요청**

```json
{
  "page_number": 3,
  "pdf_path": "/uploads/lecture.pdf",
  "chapter_title": "2장. 소프트웨어 프로세스",
  "detail": "NORMAL"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `page_number` | int (≥1) | ✅ | 현재 사용자가 보고 있는 페이지 번호 |
| `pdf_path` | string | ✅ | 업로드된 PDF — **§0** 규칙 (응답 `path` 그대로 권장) |
| `chapter_title` | string | ❌ | 챕터 제목 (없으면 "페이지 N"으로 자동 설정) |
| `detail` | `"NORMAL"` \| `"DETAILED"` | ❌ | 설명 수준 (기본: `"NORMAL"`) |

**응답**

```json
{
  "page_number": 3,
  "chapter_title": "2장. 소프트웨어 프로세스",
  "content": "소프트웨어 프로세스란 소프트웨어를 개발하기 위한..."
}
```

---

### 2-2. NDJSON 스트리밍

```
POST /api/v2/lectures/generate-stream
Content-Type: application/json
```

요청 필드는 **2-1과 동일**.

**스트리밍 이벤트 흐름**

```
{"type":"agent_delta","agent":"explainer","tool":"EXPLAIN_PAGE","channel":"thought","delta":"강의 자료 분석 중..."}
{"type":"agent_delta","agent":"explainer","tool":"EXPLAIN_PAGE","channel":"thought","delta":"핵심 개념 파악..."}
{"type":"agent_delta","agent":"explainer","tool":"EXPLAIN_PAGE","channel":"main","delta":"소프트웨어 프로세스란 "}
{"type":"agent_delta","agent":"explainer","tool":"EXPLAIN_PAGE","channel":"main","delta":"소프트웨어를 개발하기 위한..."}
{"type":"done","agent":"explainer","tool":"EXPLAIN_PAGE","final":true,"data":{}}
```

**`done.data`**: 현재 비어 있음 `{}`

> **설명 본문 포맷**: `channel: "main"`의 `delta`를 이어붙인 최종 텍스트는 **마크다운**입니다.  
> FE는 마크다운 렌더링을 권장합니다(헤딩 `##/###`, 불릿, **Bold**, 인라인 코드/코드블록, LaTeX 수식 포함 가능).  
> 또한 설명 말미에 `[질문]...[/질문]` 태그가 포함되므로, 필요하면 이 구간을 파싱해 “사고 유도 질문” UI로 분리 표시할 수 있습니다.

---

## 3. v2 — QA 평가

```
POST /api/v2/qa/evaluate
Content-Type: application/json
```

**요청**

```json
{
  "original_q": "소프트웨어 프로세스의 4가지 활동은?",
  "user_answer": "명세, 개발, 검증, 진화입니다.",
  "pdf_path": "/uploads/lecture.pdf"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `original_q` | string | ✅ | 원본 질문 |
| `user_answer` | string | ✅ | 사용자 답변 |
| `pdf_path` | string | ✅ | 강의 PDF — **§0** 규칙 |

**응답** (LLM이 생성하는 JSON)

```json
{
  "is_correct": true,
  "score": 0.9,
  "feedback": "정확합니다. 다만 '개발'은 더 구체적으로...",
  "model_answer": "소프트웨어 프로세스의 4가지 활동은..."
}
```

> LLM 응답이므로 필드 구성이 다를 수 있습니다. `feedback`과 `model_answer`는 항상 포함되도록 프롬프트가 설정돼 있습니다.

---

## 4. v2 — 시험 생성

### 4-1. 시험 프로필 수집 (대화형)

```
POST /api/v2/test-gen/profile
Content-Type: application/json
```

**요청**

```json
{
  "prompt": "Generate or validate test profile",
  "context": {
    "lecture_content": "소프트웨어 프로세스 강의 내용...",
    "exam_type": "Five_Choice",
    "topic": "소프트웨어 프로세스",
    "problem_count": 5,
    "existing_profile": null,
    "user_message": "중요한 개념 위주로 출제해주세요"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `context.lecture_content` | string | ✅ | 강의 내용 텍스트 |
| `context.exam_type` | string | ❌ | 시험 유형 (아래 참고). 스키마상 선택 — 프론트에서 이미 고른 유형을 넘기면 프로필 에이전트가 재질문하지 않음 |
| `context.topic` | string | ❌ | 출제 주제 |
| `context.problem_count` | int | ❌ | 문제 수 |
| `context.existing_profile` | object \| null | ❌ | 이전 프로필 (없으면 null) |
| `context.user_message` | string | ❌ | 사용자 추가 요청 |

**exam_type 허용 값**

| 값 | 설명 |
|---|---|
| `"Five_Choice"` | 5지선다 |
| `"OX_Problem"` | OX 문제 |
| `"Flash_Card"` | 플래시카드 |
| `"Short_Answer"` | 단답형 |
| ~~`"Debate"`~~ | ~~토론형~~ (비활성화) |

**응답**

```json
{
  "status": "COMPLETE",
  "agent_message": "프로필이 완성되었습니다. 시험을 생성할 수 있습니다.",
  "missing_info": [],
  "updated_profile": { ... }
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `"COMPLETE"` \| `"INCOMPLETE"` | `COMPLETE`이면 시험 생성 가능 |
| `agent_message` | string | 사용자에게 표시할 메시지 |
| `missing_info` | string[] | 부족한 정보 목록 (`INCOMPLETE`일 때) |
| `updated_profile` | object | 다음 호출 시 `existing_profile`로 전달 |

> **흐름**: `status: "INCOMPLETE"`이면 `agent_message`를 사용자에게 보여주고, 사용자 응답을 `user_message`로 다시 요청하세요.

---

### 4-2. 시험 문제 생성

```
POST /api/v2/test-gen/generate
Content-Type: application/json
```

**요청**

```json
{
  "exam_type": "Five_Choice",
  "target_count": 5,
  "lecture_content": "소프트웨어 프로세스 강의 내용...",
  "user_profile": { ... }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `exam_type` | string | ✅ | 시험 유형 (4-1 표 참고) |
| `target_count` | int | ✅ | 생성할 문제 수 (1~20) |
| `lecture_content` | string | ✅ | 강의 내용 |
| `user_profile` | object \| null | ❌ | 4-1에서 받은 `updated_profile` (없으면 자동 생성) |

**응답** (`TestGenerationResponse`)

```json
{
  "exam_type": "Five_Choice",
  "problems": [
    {
      "id": 1,
      "question": "소프트웨어 프로세스의 4가지 활동이 아닌 것은?",
      "options": ["명세", "개발", "검증", "진화", "설계"],
      "answer": "5",
      "explanation": "설계는..."
    }
  ],
  "total_count": 5
}
```

> 문제 구조는 `exam_type`에 따라 다릅니다. 상세 필드는 `docs/TEST_GEN_API.md` 참고.

`exam_type`이 **Debate**이면 이 엔드포인트는 **`HTTP 400`** 을 반환합니다.

---

## 5. v3 — 통합 세션 (오케스트레이션)

> 강의 설명 → 질문 → 퀴즈 → 채점의 전체 학습 흐름을 FastAPI가 자동으로 관리합니다.  
> FE는 이벤트만 보내고 스트리밍 응답을 받으면 됩니다.

### 5-1. 세션 조회/생성

```
GET /api/v3/session/by-lecture/{lectureId}?session_id={optional}&pdf_path={optional}
```

| Query | 설명 |
|---|---|
| `session_id` | 선택. 없으면 `lecture_id`로 신규 세션 키 사용 |
| `pdf_path` | 선택. **§0** 검증. 세션에 아직 PDF가 없을 때만 저장; 이미 있으면 무시 |

**응답**

```json
{
  "session_id": 42,
  "lecture_id": 7,
  "current_page": 0,
  "ai_status_connected": true,
  "created_at": "2026-03-12T10:00:00",
  "updated_at": "2026-03-12T10:00:00"
}
```

> `ai_status_connected: false`이면 세션에 `pdf_path`가 없는 상태입니다.

**`lecture_id` (이벤트 API)**  
이미 Redis에 세션이 있으면, 요청 바디의 `lecture_id`와 달라도 **저장된 세션의 `lecture_id`가 항상 사용**됩니다 (에러 없음, 서버 로그만).

---

### 5-2. 이벤트 전송 (스트리밍)

```
POST /api/v3/session/{sessionId}/event/stream
Content-Type: application/json
```

**요청 필드**

```json
{
  "type": "SESSION_ENTERED",
  "lecture_id": 7,
  "payload": {}
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `type` | AppEventType | ✅ | 이벤트 타입 (아래 표 참고) |
| `lecture_id` | int | 신규 세션만 필수 | 기존 세션에서는 생략 가능 (§5-1 참고) |
| `payload` | object | ❌ | 이벤트별 추가 데이터 (`AppEvent.get()`이 **payload**만 읽음) |

**AppEventType 목록** (payload 키는 서버 `Orchestrator` / `StateReducer`와 **반드시 일치**)

| 이벤트 | payload 예시 | 설명 |
|---|---|---|
| `SESSION_ENTERED` | `{}` | 세션 최초 진입 |
| `PAGE_CHANGED` | `{"page": 2}` | 페이지 이동 — 키는 **`page`** (정수). `page_number` 아님 |
| `START_EXPLANATION_DECISION` | `{"accept": true}` | 설명 시작 동의 — 키는 **`accept`** (boolean) |
| `USER_MESSAGE` | `{"text": "질문 본문"}` | 자유 질문 — 키는 **`text`** (`question` 아님) |
| `QUIZ_DECISION` | `{"accept": true}` | 퀴즈 진행 동의 |
| `QUIZ_TYPE_SELECTED` | `{"quizType": "OX_Problem"}` | 유형 선택 — 키는 **`quizType`** (camelCase). `Debate`면 안내 메시지 후 유형 선택 UI 재표시 |
| `QUIZ_SUBMITTED` | `{"answers": [...], "quizType": "Five_Choice"}` | 답안 제출 — `answers` 필수. `quizType` 생략 시 `Five_Choice` 가정 |
| `REVIEW_DECISION` | `{"accept": true}` | 복습 동의 |
| `RETEST_DECISION` | `{"accept": true}` | 재시험 동의 |
| `NEXT_PAGE_DECISION` | `{"accept": true}` | 다음 페이지 이동 동의 |
| `SAVE_AND_EXIT` | `{}` | 저장 후 종료 |

---

### 5-3. done.data — UI 위젯 힌트

서버는 `done.data.ui`에 **`widget`** 또는 **`modal`** 을 둡니다 (동시에 올 수 있음).

```json
{"type": "done", "final": true, "data": {"ui": {"widget": "NEXT_PAGE_DECISION"}}}
```

```json
{"type": "done", "final": true, "data": {"ui": {"modal": "QUIZ_TYPE_PICKER"}}}
```

| 키 | 값 | FE 동작 |
|---|---|---|
| `widget` | `START_EXPLANATION_DECISION` | 설명 시작 여부 UI |
| `widget` | `QUIZ_DECISION` | "퀴즈를 풀어볼까요?" |
| `widget` | `NEXT_PAGE_DECISION` | "다음 페이지로 넘어갈까요?" |
| `widget` | `REVIEW_DECISION` | 복습 제안 (채점 미달 시 등) |
| `widget` | `RETEST_DECISION` | 재시험 제안 |
| `modal` | `QUIZ_TYPE_PICKER` | 퀴즈 유형 선택 모달 |

> 상세 계약은 [`BRIDGE_API.md`](BRIDGE_API.md) §5.3 과 동일합니다.

---

## 6. v3 — Bridge (단건 퀴즈/채점)

> 세션 흐름 없이 독립적으로 퀴즈 생성 또는 채점만 필요할 때 사용합니다.

### 6-0. Bridge만의 참고 (v2.7)

- **`exam_type`**: 계약은 `Five_Choice` 등; Java `FIVE_CHOICE`는 서버가 정규화. [`BRIDGE_API.md`](BRIDGE_API.md) §7.2.
- **퀴즈 생성**에는 `pdf_path` 없음 — `lecture_content`는 텍스트만. §7.1 동일 문서.
- **`pdf_path`**: 공통 규칙은 위 **§0**.

### 6-1. 퀴즈 생성 (스트리밍)

```
POST /api/v3/bridge/quiz
Content-Type: application/json
```

**요청**

```json
{
  "exam_type": "Five_Choice",
  "lecture_content": "강의 내용...",
  "target_count": 5,
  "user_profile": null
}
```

**done.data**

```json
{
  "quiz": [ /* 문제 배열 */ ],
  "quiz_type": "Five_Choice"
}
```

---

### 6-2. 채점 (스트리밍)

```
POST /api/v3/bridge/grade
Content-Type: application/json
```

**요청**

```json
{
  "exam_type": "Five_Choice",
  "problems": [ /* done.data.quiz 배열 그대로 */ ],
  "user_answers": [
    { "problem_id": 1, "user_response": "3" },
    { "problem_id": 2, "user_response": "O" }
  ],
  "lecture_content": "",
  "pdf_path": "/uploads/lecture.pdf"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `problem_id` | int | 문제 번호 (1부터 시작) |
| `user_response` | string | MCQ/OX: 선택 번호 문자열, 단답/서술: 답변 텍스트 |
| `lecture_content` | string | 단답/서술 채점 참고 텍스트 (선택) |
| `pdf_path` | string | 단답/서술 채점 시 PDF — **있으면 텍스트보다 우선** (선택, **§0** 검증) |
| (검증) | — | `problems` 개수와 `user_answers` 개수가 **다르면 HTTP 400** |

**done.data**

```json
{
  "grading": {
    "results": [
      {
        "question_index": 0,
        "score": 1.0,
        "passed": true,
        "user_answer": "3",
        "correct_answer": "3",
        "reason": "핵심 키워드를 정확히 포함하였습니다.",
        "feedback": "정답입니다. 개념을 잘 이해하고 있습니다.",
        "deduction_reason": ""
      }
    ],
    "total_score": 0.8,
    "overall_feedback": "전반적으로 잘 이해하고 있습니다."
  },
  "passed": true
}
```

---

### 6-3. 비스트리밍 버전 (JSON 직접 반환)

| 용도 | 엔드포인트 |
|---|---|
| 퀴즈 생성 결과만 필요할 때 | `POST /api/v3/bridge/quiz/result` |
| 채점 결과만 필요할 때 | `POST /api/v3/bridge/grade/result` |

요청 필드는 스트리밍 버전과 동일합니다. 채점 단건(`grade/result`)도 **`problems`/`user_answers` 길이 일치** 및 `pdf_path` **§0** 규칙이 동일합니다.

---

## 7. 공용 — PDF 분석 / 파일 업로드

### PDF 챕터 분석

```
POST /api/pdf/analyze
Content-Type: application/json
```

```json
{ "pdf_path": "/uploads/lecture.pdf" }
```

**응답**

```json
{
  "items": [
    { "page": 1, "chapter_title": "1장. 서론" },
    { "page": 5, "chapter_title": "2장. 소프트웨어 프로세스" }
  ]
}
```

---

### 파일 업로드

```
POST /api/files/upload
Content-Type: multipart/form-data
```

```
file: (binary)
```

**응답**

```json
{
  "filename": "lecture.pdf",
  "path": "C:\\\\app\\\\uploads\\\\550e8400-e29b-41d4-a716-446655440000.pdf"
}
```

- **허용 확장자**: `.pdf`, `.md`, `.txt` 만 (`400` 그 외).
- 디스크 저장명은 **UUID + 확장자**; **`path`를 그대로** 이후 `pdf_path`에 사용하고, UI 표시는 **`filename`** 사용.

---

## 8. 스트리밍 처리 예시 코드

```typescript
async function streamLecture(req: LectureRequest) {
  const response = await fetch('/api/v2/lectures/generate-stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });

  const reader = response.body!.getReader();
  const decoder = new TextDecoder();

  let thoughtBuffer = '';
  let mainBuffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    const lines = decoder.decode(value).split('\n').filter(Boolean);

    for (const line of lines) {
      const event = JSON.parse(line);

      if (event.type === 'heartbeat') continue;  // 무시

      if (event.type === 'error') {
        showError(event.message);
        return;
      }

      if (event.type === 'agent_delta') {
        if (event.channel === 'thought') {
          // 토글 UI에 누적 표시
          thoughtBuffer += event.delta;
          updateThoughtToggle(thoughtBuffer);
        } else if (event.channel === 'main') {
          // thought 첫 번째 main 이벤트: 토글 자동 닫기
          if (mainBuffer === '') closeThoughtToggle();
          mainBuffer += event.delta;
          updateMainContent(mainBuffer);
        }
      }

      if (event.type === 'done') {
        onStreamComplete(event.data);
      }
    }
  }
}
```

---

## 9. v2 — 강의 노트 생성 (요약)

`main.py`에 등록된 **Classic Track** 엔드포인트입니다. 통합 세션(v3)과 별개입니다.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/api/v2/lecture-gen/phase1/planning` | 기획 단계 |
| `POST` | `/api/v2/lecture-gen/phase2/update` | 브리핑 갱신 |
| `POST` | `/api/v2/lecture-gen/phase3-5/auto` | Phase3~5 자동 생성 |
| `GET` | `/api/v2/lecture-gen/status/{task_id}` | 비동기 작업 상태 |

요청/응답 필드는 `app/routers/note_gen.py` 및 `docs/ARCHITECTURE.md` §2 참고.

---

> **헬스**: `GET /health` — `redis` 연결 여부 포함.  
> 전체 API 계약 상세: [`BRIDGE_API.md`](BRIDGE_API.md)  
> 시험 문제 스키마 상세: [`TEST_GEN_API.md`](TEST_GEN_API.md)  
> 아키텍처 개요: [`ARCHITECTURE.md`](ARCHITECTURE.md)
