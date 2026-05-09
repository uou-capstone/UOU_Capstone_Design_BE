# Bridge Agent Endpoints

Spring `feat/v3-springboot` 연동용 FastAPI 계약 문서다.

## 연동 기준

Spring Boot가 호출하는 최종 계약은 `/bridge/*`만 사용한다. `/api/v3/*`는 FastAPI 내부 기능 API 또는 직접 테스트/확장용으로 유지한다.

| 기능 | Spring 호출 endpoint | 응답 |
|---|---|---|
| Discussion AI Assistant | `POST /bridge/discussion_assistant_stream` | NDJSON |
| Exam Studio PDF Context | `POST /bridge/exam_studio/pdf_context` | JSON |
| Exam Studio Chat | `POST /bridge/exam_studio/chat_stream` | NDJSON |
| Student Report Chatbot | `POST /bridge/report/student_chat_stream` | NDJSON |
| Report Criteria AI 추천 | `POST /bridge/report/criteria_assistant_stream` | NDJSON |
| Classroom 종합 리포트 | `POST /bridge/report/classroom_analyze` | JSON |
| Classroom 종합 리포트 Stream | `POST /bridge/report/classroom_analyze_stream` | NDJSON |

## 인증

운영/공유 환경에서는 모든 `/bridge/*` 요청에 FastAPI `AI_SECRET_KEY`와 같은 값을 헤더로 포함한다.

```http
X-AI-SECRET-KEY: <AI_SECRET_KEY>
```

인증 예외:

- `/health`
- `/docs`
- `/redoc`
- `/openapi.json`
- `OPTIONS` preflight

로컬 호환을 위해 `AI_SECRET_KEY`가 비어 있거나 sample placeholder면 인증을 강제하지 않는다. 단, `APP_ENV=prod`, `APP_ENV=production`, `ENV=prod`, `ENV=production` 중 하나면 `AI_SECRET_KEY`가 non-placeholder 값이 아닐 때 앱 시작을 실패시킨다.

Spring WebClient 적용 방법은 `ai-service/docs/SPRING_BRIDGE_AUTH_INTEGRATION.md`를 따른다.

## NDJSON 스키마

스트리밍 응답은 `application/x-ndjson`이다. Spring은 각 line을 JSON으로 파싱한 뒤 SSE로 변환한다.

### thought_delta

```json
{ "type": "thought_delta", "text": "..." }
```

### answer_delta

```json
{ "type": "answer_delta", "text": "..." }
```

### criterion_suggestion

`/bridge/report/criteria_assistant_stream` 전용 중간 이벤트다.

```json
{ "type": "criterion_suggestion", "data": { "...": "..." } }
```

### done

```json
{ "type": "done", "data": { "...": "..." } }
```

### error

```json
{
  "type": "error",
  "code": "ERROR_CODE",
  "message": "사용자 표시용 고정 메시지",
  "details": { "errorType": "ExceptionClassName" }
}
```

## Fallback 표시 규칙

Gemini 실패나 quota/timeout 상황에서 deterministic fallback을 반환할 수 있다. Spring/FE는 다음 필드를 보고 AI 정상 결과와 fallback 결과를 구분한다.

```json
{
  "source": "AI | FALLBACK",
  "fallbackUsed": false,
  "reason": null,
  "confidence": "LOW | MEDIUM | HIGH"
}
```

Fallback 응답에서는 다음 값이 들어간다.

```json
{
  "source": "FALLBACK",
  "fallbackUsed": true,
  "reason": "ai_fallback:...",
  "confidence": "LOW"
}
```

## Endpoints

### Discussion AI Assistant

`POST /bridge/discussion_assistant_stream`

요청:

- `courseId`
- `courseName`
- `topic`
- `category`
- `previousDraft`
- `recentDiscussions[]`
- `model`

`done.data`:

- `title`
- `contentMarkdown`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

### Exam Studio PDF Context

`POST /bridge/exam_studio/pdf_context`

요청:

- `pdfPath` 또는 `pdfText`
- `courseId`
- `lectureId`
- `materialId`
- `displayName`

응답:

- `contextId`
- `pageCount`
- `charCount`
- `expiresInSeconds`
- `cacheScope`: 현재 `PROCESS_MEMORY`
- `bestEffort`: 현재 `true`

보안/운영 제약:

- `pdfPath`는 `uploads/` 하위 경로만 허용한다.
- 확장자는 `.pdf`만 허용한다.
- PDF header는 `%PDF-`로 시작해야 한다.
- 기본 파일 크기 제한은 `EXAM_STUDIO_PDF_CONTEXT_MAX_BYTES=52428800`이다.
- `pdfText` 기본 길이 제한은 `EXAM_STUDIO_PDF_TEXT_MAX_CHARS=500000`이다.
- `contextId`는 프로세스 메모리 기반 best-effort cache다. TTL 만료, 서버 재시작, 다중 worker 라우팅 변경 시 사라질 수 있으므로 Spring은 미존재/만료 시 context를 재생성해야 한다.

### Exam Studio Chat

`POST /bridge/exam_studio/chat_stream`

요청:

- `contextId`
- `messages[]` 또는 `message`
- `currentDraft`
- `currentKstIso`
- `timeZone`
- `sourceText`
- `model`
- `responseJsonSchema`

`done.data`:

- `answerMarkdown`
- `operations[]`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

지원 operation:

- `patchExamSettings`
- `appendQuestions`
- `replaceQuestion`

### Student Report Chatbot

`POST /bridge/report/student_chat_stream`

요청:

- `context`: Spring `StudentAiReportContextResponse` shape
- `report`
- `messages[]` 또는 `question`
- `model`

`done.data`:

- `answer`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`

### Report Criteria Assistant

`POST /bridge/report/criteria_assistant_stream`

요청:

- `courseId`
- `courseName`
- `existingCriteria[]`
- `desiredCount`
- `language`
- `model`

중간 이벤트:

- `criterion_suggestion`

`done.data`:

- `suggestions[]`
- `fallbackUsed`

각 suggestion:

- `label`
- `description`
- `weight`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`

### Classroom Report

`POST /bridge/report/classroom_analyze`

`POST /bridge/report/classroom_analyze_stream`

요청:

- `courseId`
- `courseName`
- `studentReports[]`
- `criteria[]`
- `model`

응답 또는 `done.data`:

- `courseId`
- `summaryMarkdown`
- `highlights[]`
- `risks[]`
- `coachingPriorities[]`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

## FastAPI 내부 API

다음 API는 Spring 신규 bridge 계약이 아니라 FastAPI 내부/직접 테스트/확장용이다.

- `POST /api/v3/report/student/analyze`
- `POST /api/v3/report/student/analyze/stream`
- `POST /api/v3/report/student/chat/stream`
- `POST /api/v3/exam/studio/chat`
- `POST /api/v3/exam/studio/chat/stream`
- `POST /api/v3/exam/grade`
