# Bridge & Session API 계약 문서

> **대상**: Spring Boot 백엔드 개발자  
> **최종 수정**: 2026-03-12  
> **원칙**: Spring Boot는 이 문서에 명시된 필드명·타입·구조만 참고하면 됩니다.

> **📌 done.data 필드명 계약**: `§5`에 명시된 모든 필드명은 **변경되지 않습니다**.  
> API 버전 업그레이드 없이 필드명을 바꾸지 않으며, 새 필드 추가 시에도 기존 필드는 유지됩니다.

---

## 목차

1. [공통 규칙](#1-공통-규칙)
2. [세션 API `/api/session`](#2-세션-api-apisession)
3. [Bridge API `/bridge`](#3-bridge-api-bridge)
4. [스트리밍 이벤트 포맷 (agent_delta 규격)](#4-스트리밍-이벤트-포맷)
5. [done.data 상세 스펙](#5-donedata-상세-스펙)
6. [호출 패턴 가이드](#6-호출-패턴-가이드)

---

## 1. 공통 규칙

- 모든 요청/응답은 `Content-Type: application/json`
- 스트리밍 응답은 `Content-Type: application/x-ndjson` (줄마다 JSON 한 줄)
- 필드명: **snake_case** (Spring에서 camelCase로 변환 필요 시 Jackson 설정 사용)
- `session_id`, `lecture_id`: **정수(int)** 타입 고정

---

## 2. 세션 API `/api/session`

### 2.1 세션 생성/조회

```
GET /api/session/by-lecture/{lecture_id}
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `session_id` | `int` | 선택 | 기존 세션 ID. 없으면 `lecture_id`로 신규 생성 |
| `pdf_path` | `string` | 선택 | PDF 파일 경로 (신규 세션 생성 시 AI 활성화에 필요) |

**응답 (200)**

```json
{
  "session_id": 123,
  "lecture_id": 456,
  "current_page": 0,
  "ai_status_connected": true,
  "created_at": "2026-03-18T10:00:00+00:00",
  "updated_at": "2026-03-18T10:00:00+00:00"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `session_id` | `int` | 세션 ID |
| `lecture_id` | `int` | 강의 ID |
| `current_page` | `int` | 현재 페이지 번호 (0-indexed) |
| `ai_status_connected` | `bool` | PDF 경로가 설정돼 있으면 `true` |
| `created_at` | `string` | ISO 8601 생성 시각 |
| `updated_at` | `string` | ISO 8601 최종 수정 시각 |

---

### 2.2 이벤트 전송 (스트리밍) ★ 주 진입점

```
POST /api/session/{session_id}/event/stream
Content-Type: application/json
```

**요청 바디**

```json
{
  "type": "USER_MESSAGE",
  "lecture_id": 456,
  "payload": {
    "question": "소프트웨어 프로세스의 4가지 활동은?"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `type` | `AppEventType` | ✅ | 이벤트 유형 (아래 표 참고) |
| `lecture_id` | `int` | **신규 세션만 필수** | 기존 세션이면 생략 가능. 생략 시 세션의 lecture_id 사용 |
| `payload` | `object` | 선택 | 이벤트별 추가 데이터 |

**AppEventType 목록**

| 값 | payload 예시 | 설명 |
|---|---|---|
| `SESSION_ENTERED` | `{}` | 세션 진입 (환영 메시지 + 설명 시작 여부 묻기) |
| `START_EXPLANATION_DECISION` | `{"accept": true}` | 설명 시작 동의 여부 |
| `PAGE_CHANGED` | `{"page": 2}` | 페이지 이동 |
| `USER_MESSAGE` | `{"question": "..."}` | 사용자 자유 질문 |
| `QUIZ_DECISION` | `{"accept": true}` | 퀴즈 진행 동의 여부 |
| `QUIZ_TYPE_SELECTED` | `{"quizType": "OX_Problem"}` | 퀴즈 유형 선택 |
| `QUIZ_SUBMITTED` | `{"answers": [...], "quizType": "OX_Problem"}` | 퀴즈 제출 |
| `REVIEW_DECISION` | `{"accept": true}` | 복습 진행 동의 여부 |
| `RETEST_DECISION` | `{"accept": true}` | 재시험 동의 여부 |
| `NEXT_PAGE_DECISION` | `{"accept": true}` | 다음 페이지 이동 동의 |
| `SAVE_AND_EXIT` | `{}` | 저장 후 종료 |

**응답**: NDJSON 스트리밍 → [4. 스트리밍 이벤트 포맷](#4-스트리밍-이벤트-포맷) 참고

---

### 2.3 이벤트 전송 (비스트리밍)

```
POST /api/session/{session_id}/event
Content-Type: application/json
```

요청 바디는 2.2와 동일. 응답은 단건 JSON:

```json
{
  "ok": true,
  "message": "AI 응답 전체 텍스트 (스트리밍 청크 합산)",
  "ui": [{"widget": "QUIZ_DECISION"}],
  "data": [{"ui": {"widget": "QUIZ_DECISION"}}]
}
```

> ⚠ 학습 세션에서는 스트리밍(`/event/stream`)을 사용하세요. `/event`는 테스트/디버깅용입니다.

---

### 2.4 세션 상태 조회

```
GET /api/session/{session_id}/state
```

전체 세션 상태를 JSON으로 반환합니다. 프론트 초기화나 디버깅용.

---

### 2.5 세션 삭제

```
DELETE /api/session/{session_id}
```

```json
{ "ok": true, "session_id": 123 }
```

---

## 3. Bridge API `/bridge`

> 단건 AI 작업을 위임하는 엔드포인트입니다.  
> 학습 세션 흐름과 무관한 독립 작업에만 사용하세요.

### 3.1 퀴즈 생성

```
POST /bridge/quiz
Content-Type: application/json
```

**요청 바디**

```json
{
  "exam_type": "Five_Choice",
  "lecture_content": "# 운영체제\n## 프로세스 스케줄링...",
  "target_count": 5,
  "user_profile": null
}
```

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `exam_type` | `string` | ✅ | — | `Five_Choice` \| `OX_Problem` \| `Flash_Card` \| `Short_Answer` \| `Debate` |
| `lecture_content` | `string` | ✅ | — | 강의 자료 텍스트 (Markdown 권장) |
| `target_count` | `int` | 선택 | `5` | 생성할 문제 수 (1~20) |
| `user_profile` | `TestProfile \| null` | 선택 | `null` | 사용자 프로필. 없으면 기본 설정 사용 |

**응답**: NDJSON 스트리밍. `done` 이벤트의 `data` → [5.1 퀴즈 done.data](#51-퀴즈-생성-donedataquiz) 참고

---

### 3.2 채점

```
POST /bridge/grade
Content-Type: application/json
```

**요청 바디**

```json
{
  "exam_type": "Five_Choice",
  "problems": [
    {
      "id": 1,
      "question_content": "SJF의 특징은?",
      "options": [...],
      "correct_answer": "2"
    }
  ],
  "user_answers": [
    { "problem_id": 1, "user_response": "2" }
  ],
  "lecture_content": "..."
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `exam_type` | `string` | ✅ | 시험 유형 |
| `problems` | `array` | ✅ | **퀴즈 생성 응답의 `done.data.quiz` 배열 그대로 전달** |
| `user_answers` | `UserAnswer[]` | ✅ | 사용자 답안 목록 |
| `lecture_content` | `string` | 선택 | 단답/서술형 채점 시 참고용 강의 자료 |

**`UserAnswer` 구조**

| 필드 | 타입 | 설명 |
|---|---|---|
| `problem_id` | `int` | 문제 ID (문제 객체의 `id` 값) |
| `user_response` | `string` | 5지선다: `"1"`~`"5"` / OX: `"O"` 또는 `"X"` / 단답: 텍스트 |

**응답**: NDJSON 스트리밍. `done` 이벤트의 `data` → [5.2 채점 done.data](#52-채점-donedatagrading) 참고

---

## 4. 스트리밍 이벤트 포맷

모든 스트리밍 응답은 **NDJSON** (줄마다 독립 JSON) 형식입니다.

### 4.1 이벤트 타입 및 필드

| `type` | 설명 | 항상 보장 |
|---|---|---|
| `agent_delta` | 텍스트 청크 스트리밍 | `agent`, `channel`, `delta` |
| `done` | 작업 완료. `data`에 결과 포함 | `agent`, `final: true`, `data` |
| `error` | 오류 발생. **스트림은 반드시 이 이벤트로 종료됨** | `agent`, `message` |
| `heartbeat` | 연결 유지용 keep-alive. **클라이언트가 무시해도 됨** | `type` 만 포함 |

**전체 필드 목록**

| 필드 | 타입 | 포함 조건 | 설명 |
|---|---|---|---|
| `type` | `string` | 항상 | 이벤트 타입 |
| `agent` | `string` | `heartbeat` 제외 | `"explainer"` \| `"qa"` \| `"quiz"` \| `"grader"` \| `"system"` |
| `tool` | `string` | 선택 | 호출된 도구 이름 |
| `channel` | `string` | `agent_delta`만 | `"thought"` (내부 추론) \| `"main"` (실제 답변) |
| `delta` | `string` | `agent_delta`만 | 텍스트 청크 (짧은 단위로 자주 전송됨) |
| `final` | `bool` | `done`만 | 항상 `true` |
| `data` | `object` | `done`만 | 결과 데이터 (§5 참고) |
| `message` | `string` | `error`만 | 오류 메시지 |

### 4.2 이벤트 흐름 예시 (퀴즈 생성)

```jsonl
{"type": "agent_delta", "agent": "quiz", "tool": "GENERATE_QUIZ", "channel": "thought", "delta": "Five_Choice 유형 퀴즈 5문항을 생성 중입니다..."}
{"type": "heartbeat"}
{"type": "heartbeat"}
{"type": "done", "agent": "quiz", "tool": "GENERATE_QUIZ", "final": true, "data": {"quiz": [...], "quiz_type": "Five_Choice"}}
```

### 4.3 오류 보장

```
# 어떤 오류가 발생해도 스트림 마지막 줄은 반드시 type:error 이벤트
{"type": "error", "agent": "quiz", "message": "퀴즈 생성 실패 (Five_Choice): ..."}
```

### 4.4 Spring Boot 처리 권장 패턴

```java
for (String line : ndjsonLines) {
    JsonNode node = objectMapper.readTree(line);
    String type = node.get("type").asText();

    switch (type) {
        case "agent_delta":
            if ("main".equals(node.path("channel").asText())) {
                // UI에 delta 스트리밍 출력
            }
            // "thought" 채널 → 로딩 인디케이터 (선택)
            break;
        case "done":
            // data 파싱 후 UI 갱신 (§5 참고)
            break;
        case "error":
            // 오류 처리 (message 필드 참고)
            break;
        case "heartbeat":
            // 무시 (연결 유지용)
            break;
    }
}
```

---

## 5. done.data 상세 스펙 (필드명 계약)

> **⚠️ 계약 공지**: 이 섹션에 명시된 모든 필드명은 **Breaking Change 없이 변경되지 않습니다.**  
> 새 필드가 추가되더라도 기존 필드는 그대로 유지됩니다. 파싱 시 미지정 필드는 무시해주세요.

---

### 5.1 퀴즈 생성 완료 이벤트

트리거: `/bridge/quiz` 완료 또는 세션 내 퀴즈 생성 완료

```json
{
  "type": "done",
  "agent": "quiz",
  "tool": "GENERATE_QUIZ",
  "final": true,
  "data": {
    "quiz": [ /* 문제 배열 */ ],
    "quiz_type": "Five_Choice"
  }
}
```

**`data` 최상위 필드 (계약)**

| 필드명 | 타입 | 설명 |
|---|---|---|
| `quiz` | `array` | 문제 배열. **채점 시 `/bridge/grade`의 `problems` 필드로 그대로 전달** |
| `quiz_type` | `string` | 생성된 퀴즈 유형 (`Five_Choice` \| `OX_Problem` \| `Flash_Card` \| `Short_Answer` \| `Debate`) |

`data.quiz` 배열 내부 구조는 `quiz_type`별로 다릅니다. 상세 필드는 `TEST_GEN_API.md` §4 참고.

**`data.quiz` 공통 필드 (모든 유형에 포함)**

| 필드명 | 타입 | 설명 |
|---|---|---|
| `id` | `int` | 문제 고유 ID. 채점 시 `problem_id`에 이 값을 사용 |
| `question_content` | `string` | 문제 내용 |

---

### 5.2 채점 완료 이벤트

트리거: `/bridge/grade` 완료 또는 세션 내 채점 완료

```json
{
  "type": "done",
  "agent": "grader",
  "tool": "GRADE",
  "final": true,
  "data": {
    "grading": {
      "results": [
        {
          "question_index": 0,
          "score": 1.0,
          "passed": true,
          "feedback": "정답입니다!",
          "user_answer": "2",
          "correct_answer": "2"
        }
      ],
      "total_score": 0.8,
      "overall_feedback": "5문항 중 4문항 정답 (80점)"
    },
    "passed": true
  }
}
```

**`data` 최상위 필드 (계약)**

| 필드명 | 타입 | 설명 |
|---|---|---|
| `grading` | `object` | 채점 결과 상세 |
| `passed` | `bool` | 합격 여부 (`total_score ≥ 0.6` → `true`) |

**`data.grading` 필드 (계약)**

| 필드명 | 타입 | 설명 |
|---|---|---|
| `results` | `array` | 문제별 채점 결과 배열 |
| `total_score` | `float` | 전체 평균 점수 (0.0 ~ 1.0) |
| `overall_feedback` | `string` | 전체 총평 문자열 |

**`data.grading.results[]` 필드 (계약)**

| 필드명 | 타입 | 설명 |
|---|---|---|
| `question_index` | `int` | 문제 인덱스 (0-indexed, `problems` 배열 기준) |
| `score` | `float` | 문제별 점수 (0.0 ~ 1.0) |
| `passed` | `bool` | 문제별 통과 여부 (score ≥ 0.6) |
| `feedback` | `string` | 문제별 피드백 |
| `user_answer` | `string` | 학생이 제출한 답 (MCQ/OX만 포함) |
| `correct_answer` | `string` | 정답 (MCQ/OX만 포함) |

> **참고**: `user_answer`와 `correct_answer`는 MCQ/OX 자동 채점 시만 포함됩니다.  
> 단답/서술형 LLM 채점 시에는 이 필드가 없을 수 있습니다.

**`data.passed` 해석**

| 값 | 의미 | 이후 동작 |
|---|---|---|
| `true` | 합격 (≥60점) | NEXT_PAGE_DECISION 위젯 표시 |
| `false` | 불합격 (<60점) | REVIEW_DECISION 위젯 표시 |

---

### 5.3 UI 위젯 `done.data.ui`

FastAPI가 특정 UI 요소를 표시하도록 Spring Boot에 신호를 보낼 때:

```json
{
  "type": "done",
  "agent": "system",
  "final": true,
  "data": {
    "ui": { "widget": "QUIZ_DECISION" }
  }
}
```

| `widget` 값 | 표시 시점 | 설명 |
|---|---|---|
| `QUIZ_DECISION` | 설명 완료 후 | "퀴즈를 풀어볼까요?" 동의 요청 |
| `QUIZ_TYPE_PICKER` | 퀴즈 진행 동의 후 | 퀴즈 유형 선택 UI |
| `REVIEW_DECISION` | 채점 불합격 후 | "복습을 진행할까요?" 동의 요청 |
| `NEXT_PAGE_DECISION` | 모든 흐름 완료 후 | "다음 페이지로 이동할까요?" |

---

## 6. 호출 패턴 가이드

### ✅ 학습 세션 전체 흐름

```
1. GET /api/session/by-lecture/{lectureId}?pdf_path=...
   → session_id 수령

2. POST /api/session/{session_id}/event/stream
   body: { "type": "SESSION_ENTERED", "lecture_id": 456 }
   → 환영 메시지 + QUIZ_DECISION 위젯 수신

3. POST /api/session/{session_id}/event/stream
   body: { "type": "START_EXPLANATION_DECISION", "payload": {"accept": true} }
   ← lecture_id 생략 가능 (세션에 저장됨)
   → 강의 설명 스트리밍 수신

4. (계속) USER_MESSAGE, QUIZ_TYPE_SELECTED, QUIZ_SUBMITTED ...
```

### ✅ 단건 퀴즈 생성 + 채점

```
1. POST /bridge/quiz
   body: { "exam_type": "OX_Problem", "lecture_content": "...", "target_count": 5 }
   → done.data.quiz 배열 수신 (저장해둠)

2. POST /bridge/grade
   body: {
     "exam_type": "OX_Problem",
     "problems": [/* done.data.quiz 그대로 */],
     "user_answers": [{ "problem_id": 1, "user_response": "O" }, ...]
   }
   → done.data.grading 수신
```

### ❌ 금지 패턴

```
# Spring Boot가 bridge를 순서대로 호출해 흐름 구성 → FastAPI의 오케스트레이션 원칙 위반
POST /bridge/explain → POST /bridge/qa → POST /bridge/grade
```
