# Bridge Agent Endpoints

Spring `feat/v3-springboot` 연동용 FastAPI 계약 문서다.

## 연동 기준

Spring Boot가 호출하는 최종 계약은 `/bridge/*`만 사용한다. `/api/v3/*`는 FastAPI 내부 기능 API 또는 직접 테스트/확장용으로 유지한다.

| 기능 | Spring 호출 endpoint | 응답 |
|---|---|---|
| Discussion AI Assistant | `POST /bridge/discussion_assistant_stream` | NDJSON |
| Exam Studio PDF Context | `POST /bridge/exam_studio/pdf_context` | JSON |
| Exam Studio Chat | `POST /bridge/exam_studio/chat_stream` | NDJSON |
| Teacher Exam Grade | `POST /bridge/exam/grade` | JSON |
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
  "details": { "errorType": "AI_QUOTA" }
}
```

`details.errorType`은 내부 예외 class name을 노출하지 않고 다음 stable code 중 하나만 사용한다.

- `VALIDATION_ERROR`
- `AUTH_ERROR`
- `AI_TIMEOUT`
- `AI_QUOTA`
- `AI_UNAVAILABLE`
- `INTERNAL_ERROR`

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
  "reason": "AI_QUOTA",
  "confidence": "LOW"
}
```

`reason`도 stable code를 사용한다. Spring/FE는 `fallbackUsed`, `source`, `reason`을 기준으로 사용자에게 대체 결과임을 표시한다.

## 메시지 우선순위

대화형 endpoint에서 `messages[]`와 shortcut field가 동시에 들어오면 다음 순서를 따른다.

1. `messages[]`에 마지막 `role=user` 메시지가 있으면 이를 현재 질문으로 사용한다.
2. `messages[]`에 user 메시지가 없으면 `message` 또는 `question` shortcut field를 사용한다.
3. 둘 다 없으면 `400`을 반환한다.

Endpoint별 shortcut field:

- `/bridge/exam_studio/chat_stream`: `message`
- `/bridge/report/student_chat_stream`: `question`

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

후처리 정책:

- `contentMarkdown`에서 중복 제목 heading은 제거될 수 있다.
- 인사말, assistant 자기소개, `AI` 언급 등 게시글 초안에 부적절한 표현은 제거된다.
- 제거/보정 사유는 `warnings[]`에 `DISCUSSION_*` 코드로 포함된다.

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

- `pdfPath`는 FastAPI 프로세스 또는 FastAPI 컨테이너 filesystem 기준 경로다. Spring 서버 로컬 경로가 아니다.
- `pdfPath`는 FastAPI의 `uploads/` 하위 경로만 허용한다.
- 운영에서 Spring이 `pdfPath`를 넘기려면 Spring과 FastAPI가 같은 volume mount 경로를 공유해야 한다.
- volume 공유가 어렵다면 Spring은 `pdfText`를 넘기거나, FastAPI가 접근 가능한 업로드 경로를 먼저 만들어야 한다.
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
- `responseJsonSchema`: optional. 생략하면 FastAPI 내장 기본 schema를 사용한다. Spring은 일반적으로 생략한다.

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

operation 검증:

- FastAPI는 LLM 응답의 `operations[]`를 실행 가능한 계약 형태로 한 번 더 검증한다.
- 지원하지 않는 `method`, 빈 `params`, 잘못된 ISO 날짜, 빈 문항 추가, 잘못된 문항 교체 요청은 제거된다.
- 문항은 `prompt`, `type`, `points`를 검증한다.
- `points`는 0.5~100 범위로 clamp 된다.
- MCQ는 `choices` 2개 이상과 실제 choice id에 존재하는 `answer.choiceId`가 필요하다.
- OX는 `answer.value`가 `O/X`로 정규화 가능해야 한다.
- SHORT/ESSAY는 reference answer 또는 rubric이 없으면 제거된다.
- 제거/보정 사유는 `done.data.warnings[]`에 들어간다.
- 최종 `operations[]`는 최대 8개다.
- `appendQuestions.params.questions`는 최대 50개다.
- 변경 의도가 명확한 요청에서 검증 후 `operations[]`가 비면 `source="FALLBACK"`, `reason="VALIDATION_ERROR"`로 응답한다.

문항 생성 요청 보정:

- `message` 또는 `messages[]`의 마지막 user 메시지가 문항 추가/생성/출제를 요구하면 PDF/강의자료 context가 필요하다.
- context 기준은 FastAPI가 받은 `contextId`의 캐시 텍스트 또는 요청의 `sourceText`다.
- context가 없으면 문항을 생성하지 않고 `operations=[]`, `source="FALLBACK"`, `fallbackUsed=true`, `reason="MISSING_CONTEXT"`로 안내한다.
- context가 있는데 Gemini가 빈 `operations[]` 또는 빈 `appendQuestions.params.questions`를 반환하면 FastAPI가 deterministic 문항을 생성해 `appendQuestions`를 보정한다.
- 이 경우 `source="FALLBACK"`, `fallbackUsed=true`, `reason="VALIDATION_ERROR"`로 표시한다.

### Teacher Exam Grade

`POST /bridge/exam/grade`

요청:

- `exam`
- `answers`
- `model`
- `responseJsonSchema`: optional. 생략하면 FastAPI 내장 기본 schema를 사용한다. Spring은 일반적으로 생략한다.

응답:

- `totalScore`
- `maxScore`
- `scoreRatio`
- `items[]`
- `summaryMarkdown`
- `gradingSource`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

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
- `source`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

각 suggestion:

- `label`
- `description`
- `weight`
- `source`
- `fallbackUsed`
- `reason`
- `confidence`
- `warnings[]`

추천 정규화:

- 기존 criteria label과 중복되는 추천은 제거된다.
- 최종 `suggestions[].weight` 합은 100이 되도록 재분배된다.
- AI 추천 수가 부족하면 deterministic fallback 기준으로 채운다.

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

Stream 이벤트 순서:

1. `thought_delta`
2. `answer_delta`
3. `done`

## FastAPI 내부 API

다음 API는 Spring 신규 bridge 계약이 아니라 FastAPI 내부/직접 테스트/확장용이다.

- `POST /api/v3/report/student/analyze`
- `POST /api/v3/report/student/analyze/stream`
- `POST /api/v3/report/student/chat/stream`
- `POST /api/v3/exam/studio/chat`
- `POST /api/v3/exam/studio/chat/stream`
- `POST /api/v3/exam/grade`

Spring 교사용 시험 채점 연동은 `/api/v3/exam/grade`가 아니라 `POST /bridge/exam/grade`를 사용한다.
