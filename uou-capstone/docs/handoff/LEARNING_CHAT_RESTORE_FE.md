# Learning Chat Restore FE Guide

## Goal

통합학습 화면에서 사용자가 브라우저 뒤로가기, 라우트 이동, 새로고침 후 같은 강의에 다시 진입해도 기존 에이전트 대화 내용을 복원한다.

BE는 `POST /api/learning/sessions/{lectureId}` 호출 시 `sessionId`가 없으면 같은 사용자 + 같은 강의의 종료되지 않은 최신 채팅 세션을 재사용한다. FE는 응답의 `chatSessionId`로 메시지 히스토리를 다시 조회해서 화면 state에 넣어야 한다.

## Required Flow

### Material replacement/deletion rule

When a lecture PDF is deleted or replaced, BE ends the active Spring chat sessions for that lecture and invalidates the matching FastAPI sessions by `chatSessionId`.

After material deletion or replacement, FE must discard the previously stored `chatSessionId` for that lecture and call `POST /api/learning/sessions/{lectureId}` again. Do not continue sending events with the old `chatSessionId`; BE rejects ended sessions before calling FastAPI.

### 1. Enter lecture learning screen

```http
POST /api/learning/sessions/{lectureId}
Authorization: Bearer {accessToken}
```

Optional query:

- `pdfPath`: FE가 직접 PDF 경로를 알고 있을 때만 전달. 생략하면 BE가 강의 최신 PDF를 조회한다.
- `sessionId`: 특정 기존 세션을 명시적으로 이어갈 때만 전달. 일반 재입장 복원에서는 생략한다.

Response fields to use:

```json
{
  "session_id": 12,
  "chatSessionId": 12,
  "lecture_id": 100,
  "current_page": 1,
  "ai_status_connected": true
}
```

Use `chatSessionId` as the FE source of truth for Spring chat history APIs.

### 2. Restore messages immediately after session response

```http
GET /api/learning/sessions/{chatSessionId}/messages
Authorization: Bearer {accessToken}
```

Response:

```json
[
  {
    "messageId": 1,
    "role": "USER",
    "content": "현재 페이지 설명해줘",
    "pageNumber": 1,
    "createdAt": "2026-06-01T18:20:30"
  },
  {
    "messageId": 2,
    "role": "ASSISTANT",
    "content": "이 페이지는 Industry 4.0의 핵심 개념을 설명합니다.",
    "pageNumber": 1,
    "createdAt": "2026-06-01T18:20:45"
  }
]
```

Map roles:

- `USER` -> user bubble
- `ASSISTANT` -> agent/assistant bubble

If the array is empty, show the current empty state. Do not show `메시지가 없습니다` before this request finishes.

### 3. Send new events with the same `chatSessionId`

```http
POST /api/learning/sessions/{chatSessionId}/event?lectureId={lectureId}&page={currentPage}
Authorization: Bearer {accessToken}
Content-Type: application/json
Accept: text/event-stream
```

User question body:

```json
{
  "type": "USER_MESSAGE",
  "payload": {
    "question": "이 페이지 핵심만 요약해줘"
  }
}
```

Use `question`, not `text`, for new FE code.

### 4. Exit behavior

Only send `SAVE_AND_EXIT` when the user intentionally ends the learning session.

```json
{
  "type": "SAVE_AND_EXIT",
  "payload": {}
}
```

Do not send `SAVE_AND_EXIT` for browser back, route leave, tab switch, or page refresh if the expected UX is "resume when returning". Once BE marks the session ended, normal lecture re-entry creates or selects another active session instead of resuming the ended one.

## React-style Example

```ts
type LearningMessage = {
  messageId: number;
  role: "USER" | "ASSISTANT";
  content: string;
  pageNumber: number | null;
  createdAt: string;
};

async function enterLearning(lectureId: number, token: string) {
  const sessionRes = await fetch(`/api/learning/sessions/${lectureId}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (!sessionRes.ok) {
    throw new Error("학습 세션을 불러오지 못했습니다.");
  }

  const session = await sessionRes.json();
  const chatSessionId = session.chatSessionId;

  const messagesRes = await fetch(`/api/learning/sessions/${chatSessionId}/messages`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (!messagesRes.ok) {
    throw new Error("학습 메시지를 불러오지 못했습니다.");
  }

  const messages: LearningMessage[] = await messagesRes.json();
  return { session, chatSessionId, messages };
}
```

Suggested component state order:

1. Set `isRestoringMessages = true`.
2. Call `POST /api/learning/sessions/{lectureId}`.
3. Store `chatSessionId`.
4. Call `GET /api/learning/sessions/{chatSessionId}/messages`.
5. Set messages from response.
6. Set `isRestoringMessages = false`.

## Common Pitfalls

- Do not use only `session_id` if the chat history API expects `chatSessionId`.
- Do not create a new client-side session ID on every mount.
- Do not clear messages after session creation unless the history request returned an empty array.
- Do not show the empty state while the history request is still loading.
- Do not send `SAVE_AND_EXIT` from route cleanup if users should be able to resume.
- After material deletion or replacement, do not reuse the old `chatSessionId`; re-enter the lecture session API and use the new `chatSessionId`.

## Smoke Test

1. Enter a lecture in 통합학습.
2. Send a question and wait for the assistant answer to finish.
3. Click browser back or navigate to another in-app route without ending the session.
4. Re-enter the same lecture.
5. Expected: previous user and assistant messages are visible before sending a new message.
6. Send another question.
7. Expected: the new event uses the restored `chatSessionId`, and messages continue in the same thread.
