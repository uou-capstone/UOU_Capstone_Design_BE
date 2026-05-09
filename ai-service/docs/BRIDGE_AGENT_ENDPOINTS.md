# Bridge Agent Endpoints

Spring `feat/v3-springboot` 연동용 FastAPI compatibility endpoints.

## 인증

운영/공유 환경에서는 FastAPI `AI_SECRET_KEY`와 같은 값을 모든 요청 헤더에 포함한다.

- Header: `X-AI-SECRET-KEY: <AI_SECRET_KEY>`
- `/health`, `/docs`, `/redoc`, `/openapi.json`은 인증 예외다.
- 로컬 호환을 위해 `AI_SECRET_KEY`가 비어 있거나 sample placeholder면 인증을 강제하지 않는다.

## NDJSON 규칙

스트리밍 응답은 `application/x-ndjson` 이며 이벤트는 다음 형태를 사용한다.

- `thought_delta`: `{ "type": "thought_delta", "text": "..." }`
- `answer_delta`: `{ "type": "answer_delta", "text": "..." }`
- `criterion_suggestion`: `{ "type": "criterion_suggestion", "data": {...} }`
- `done`: `{ "type": "done", "data": {...} }`
- `error`: `{ "type": "error", "code": "...", "message": "..." }`

## Endpoints

### Discussion AI Assistant

`POST /bridge/discussion_assistant_stream`

입력:

- `courseId`
- `courseName`
- `topic`
- `category`
- `previousDraft`
- `recentDiscussions[]`

완료 데이터:

- `title`
- `contentMarkdown`
- `source`
- `warnings[]`

### Exam Studio

`POST /bridge/exam_studio/pdf_context`

입력:

- `pdfPath` 또는 `pdfText`
- `courseId`, `lectureId`, `materialId`, `displayName`

응답:

- `contextId`
- `pageCount`
- `charCount`
- `expiresInSeconds`

`POST /bridge/exam_studio/chat_stream`

입력:

- `contextId`
- `messages[]` 또는 `message`
- `currentDraft`
- `currentKstIso`
- `timeZone`
- `sourceText`

완료 데이터:

- `answerMarkdown`
- `operations[]`
- `source`
- `warnings[]`

### Student Report Chatbot

`POST /bridge/report/student_chat_stream`

입력:

- `context`: Spring `StudentAiReportContextResponse` shape
- `report`
- `messages[]` 또는 `question`

완료 데이터:

- `answer`

### Report Criteria Assistant

`POST /bridge/report/criteria_assistant_stream`

입력:

- `courseId`
- `courseName`
- `existingCriteria[]`
- `desiredCount`

중간 이벤트:

- `criterion_suggestion`

완료 데이터:

- `suggestions[]`

### Classroom Report

`POST /bridge/report/classroom_analyze`

`POST /bridge/report/classroom_analyze_stream`

입력:

- `courseId`
- `courseName`
- `studentReports[]`
- `criteria[]`

완료 데이터:

- `summaryMarkdown`
- `highlights[]`
- `risks[]`
- `coachingPriorities[]`
- `source`
- `warnings[]`
