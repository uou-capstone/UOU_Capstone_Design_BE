# v2.7 E2E Smoke Test (Spring ↔ FastAPI)

## 목적
- FastAPI `v2.7` 기준으로 **v2/v3 핵심 연동이 깨지지 않았는지** 빠르게 확인한다.
- 실패 시 “경로 문제(404/405)” vs “계약 문제(400/422)”를 구분한다.

## 준비값
- `SPRING_BASE_URL` 예: `http://localhost:8081`
- 선택: `FASTAPI_BASE_URL` 예: `http://localhost:8000` 또는 ngrok URL
- 선택: `LECTURE_ID` 예: `1`
- 선택: `TEACHER_JWT` (Spring 보호 API 확인 시)
- 선택: `PDF_PATH` (FastAPI `/api/files/upload` 응답의 `path` 값)
  - v2.7부터 `path`는 UUID 기반이며 **상대 경로일 수 있음** (예: `uploads/<uuid>.pdf`)
  - 프론트/스프링은 이 값을 **파싱/가공하지 말고 그대로** `pdf_path`로 전달

## PASS/WARN/FAIL 기준
- `PASS`: 2xx, 또는 계약상 자연스러운 4xx(예: 400/422)로 **경로가 살아있음**이 확인됨
- `WARN`: 동작은 하지만 응답 필드 차이/형식 차이 존재(추가 확인 필요)
- `FAIL`: 404/405, 타임아웃, 5xx

---

## A. FastAPI 직접 스모크 (v2.7 계약 확인)

### A-1) Health
- `GET {FASTAPI_BASE_URL}/health`
- 기대: `200`

### A-2) 파일 업로드 (선택)
- `POST {FASTAPI_BASE_URL}/api/files/upload` (multipart)
- 기대: `200` + `{ filename, path }`
- 주의:
  - 표시용: `filename`
  - API 전달용: `path` (v2.7부터 UUID/상대경로 가능)

### A-3) v3 Session by Lecture
- `GET {FASTAPI_BASE_URL}/api/v3/session/by-lecture/{LECTURE_ID}?pdf_path={PDF_PATH}`
- 기대:
  - `200`
  - `session_id` 포함

### A-4) v3 Session Event (non-stream)
- `POST {FASTAPI_BASE_URL}/api/v3/session/{session_id}/event`
- 기대: 404/405가 아니어야 함
- 참고(v2.7): 기존 세션에 다른 `lecture_id`를 보내도 세션 값이 우선(덮어쓰기 차단)

### A-5) v3 Bridge Grade Result (길이 검증)
- `POST {FASTAPI_BASE_URL}/api/v3/bridge/grade/result`
- 기대:
  - `problems.length != user_answers.length`이면 **400**
  - 같으면 404/405가 아니어야 함 (422도 경로 생존 관점에서 PASS)

---

## B. Spring 경유 스모크 (실사용 경로)

### B-0) Spring 기본 동작(인증 없이)
- `GET {SPRING_BASE_URL}/actuator/health`
- `GET {SPRING_BASE_URL}/swagger-ui.html`
- 기대:
  - 둘 다 404/5xx가 아니어야 함
  - `swagger-ui` 경로는 springdoc 설정에 따라 다를 수 있음 (로컬에서 확인)

### B-1) v3 Learning Session 시작
- `POST {SPRING_BASE_URL}/api/learning/sessions/{LECTURE_ID}?pdfPath={PDF_PATH}`
- Header: `Authorization: Bearer {TEACHER_JWT}` (또는 학생 토큰)
- 기대: 2xx + session 응답

### B-2) v3 Learning Session 이벤트 스트림
- `POST {SPRING_BASE_URL}/api/learning/sessions/{sessionId}/event?lectureId={LECTURE_ID}`
- Header: `Authorization: Bearer {TEACHER_JWT}`
- 기대:
  - SSE 수신
  - `heartbeat` 무시
  - `done.data.ui.widget` 기반 UI 전환 가능(v2.7)

### B-3) 시험 생성/채점 (Bridge 연동)
- 생성: `POST {SPRING_BASE_URL}/api/exams/generation` (TEACHER)
- 채점: `POST {SPRING_BASE_URL}/api/exams/submission` (STUDENT)
- 기대(v2.7):
  - Bridge 채점 시 problems/answers 길이 불일치로 실패하지 않도록 FE/Spring에서 선검증
  - 토론형(DEBATE)은 Bridge 비활성 → `/api/exams/debate/*` 사용

#### B-3 스모크 스크립트 해석 가이드
- **B-3a** (`/api/exams/generation`)
  - `FLASH_CARD` + `targetCount=5` 최소 바디로 호출
  - 2xx: PASS (실제 생성 성공) / 4xx(400·422·403 등): PASS (경로 생존) / 404·405·타임아웃: FAIL
- **B-3b** (`/api/exams/submission`)
  - 존재하지 않는 `examSessionId=0`으로 호출하므로 정상 동작 시 **404(BusinessException)** 이 정상 응답
  - `Check-Not404Or405` 대신 전용 분기로 404 / 4xx를 PASS 처리, 405·타임아웃만 FAIL
  - 실제 채점 플로우는 생성된 세션 ID가 필요하므로 별도 시나리오에서 확인

---

## 자동 실행 스크립트
- `scripts/smoke-v27.ps1`

예시:
- Spring 단독(기본값: `http://localhost:8081`):
  - `pwsh ./scripts/smoke-v27.ps1`
- FastAPI 포함:
  - `pwsh ./scripts/smoke-v27.ps1 -FastApiBaseUrl http://localhost:8000 -LectureId 1`
  - `pwsh ./scripts/smoke-v27.ps1 -FastApiBaseUrl http://localhost:8000 -LectureId 1 -PdfPath \"uploads/xxxx.pdf\"`
- Spring 보호 API까지:
  - `pwsh ./scripts/smoke-v27.ps1 ... -TeacherJwt <JWT>`

