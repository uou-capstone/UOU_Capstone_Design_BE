# 학생용 시험 조회 + 응시 정상화 — 프론트 인계 문서

학생이 강의실에서 시험 카드를 눌러 문제를 보고 응시까지 정상 완료할 수 있도록 신규 API 1종을 추가하고, 생성/응시 사이의 데이터 단절을 함께 수정했다. **이 문서는 FE 가 호출해야 하는 Spring API 와 마이그레이션 절차만 다룬다.**

- 작성: 2026-05-15
- 백엔드 브랜치: `feat/v3-springboot`
- 관련 도메인: `uou-capstone/src/main/java/io/github/uou_capstone/aiplatform/domain/exam`

---

## TL;DR (3줄)

1. **신규** — `GET /api/exams/student/{examSessionId}` (STUDENT 전용). 학생용 화면은 시험 카드 클릭 시 이 API 만 호출한다. 정답·해설·평가 기준은 응답에 포함되지 않는다.
2. **기존** — `POST /api/exams/submission` / `GET /api/exams/submission/{examResultId}` 변경 없음. 단, **응시 요청의 `answers` 배열은 본 조회 응답의 문제 순서대로 보내야 한다** (서버는 현재 개수만 검증).
3. **DEBATE** — `debateTopics: []` 빈 리스트가 내려간다. DEBATE 카드 클릭 시 본 API 가 아닌 기존 `/api/exams/debate/start` 로 진입.

---

## 1) 신규 엔드포인트

| 항목 | 값 |
|---|---|
| 메서드/경로 | `GET /api/exams/student/{examSessionId}` |
| 권한 | `STUDENT` 전용 + 해당 강의 Course 에 **ACTIVE 수강** 필요 |
| 인증 | Bearer JWT |
| 응답 Content-Type | `application/json` |

### 1-1. 권한 규칙

- 컨트롤러 단: `@PreAuthorize("hasAuthority('STUDENT')")` — TEACHER 호출 시 403.
- 서비스 단: `CourseAccessService.loadCourseAsParticipant(courseId)` — 해당 강의의 ACTIVE 수강생만 통과. `COMPLETED` / `DROPPED` 수강은 403.
- 시험 상태: `READY` 가 아니면 `INVALID_PHASE` 에러 (생성 중·실패 상태는 학생에 노출 안 됨).

### 1-2. 에러 응답

| 상황 | HTTP | code |
|---|---|---|
| 미인증 | 401 | `UNAUTHORIZED` |
| 권한 부족 / 비수강 / TEACHER 시도 | 403 | `FORBIDDEN` |
| examSessionId 없음 | 404 | `SESSION_NOT_FOUND` |
| 시험이 READY 가 아님 | 409 | `INVALID_PHASE` |

---

## 2) 응답 스키마 — 시험 유형별

공통 헤더는 동일하다. `examType` 값에 따라 아래 5개 리스트 중 정확히 하나가 채워진다.

```jsonc
{
  "examSessionId": 101,
  "materialId": 17,            // null 가능
  "examType": "FLASH_CARD",    // FLASH_CARD | OX_PROBLEM | FIVE_CHOICE | SHORT_ANSWER | DEBATE
  "displayName": "1주차 핵심 개념 확인",
  "totalCount": 5,
  "flashCards": [...],         // examType 에 해당하는 리스트만 채워짐, 나머지는 null
  "oxProblems": null,
  "fiveChoiceProblems": null,
  "shortAnswerProblems": null,
  "debateTopics": null
}
```

### 2-1. FLASH_CARD

```jsonc
"flashCards": [
  {
    "id": 5001,                // ExamQuestion.id  (응시 매핑 시 사용)
    "frontContent": "Polymorphism 이란?",
    "categoryTag": "객체지향",
    "complexityLevel": "Intermediate"
    // backContent (정답) 미포함
  }
]
```

### 2-2. OX_PROBLEM

```jsonc
"oxProblems": [
  {
    "id": 5101,
    "questionContent": "Java 의 모든 클래스는 Object 를 상속한다."
    // correctAnswer / explanation / intentType 미포함
  }
]
```

### 2-3. FIVE_CHOICE

```jsonc
"fiveChoiceProblems": [
  {
    "id": 5201,
    "questionContent": "다음 중 다형성의 예가 아닌 것은?",
    "options": [
      { "id": "1", "content": "메서드 오버라이딩" },
      { "id": "2", "content": "메서드 오버로딩" },
      { "id": "3", "content": "인터페이스 구현" },
      { "id": "4", "content": "static 변수 선언" },
      { "id": "5", "content": "추상 클래스 상속" }
    ]
    // correctAnswer / intentDiagnosis / option.intent / option.isCorrect 미포함
  }
]
```

> **주의**: `options[].id` 는 원본 옵션 번호 문자열(`"1"`~`"5"`)이다. **DB ChoiceOption.id 가 아니다.** 학생이 보낸 `selectedOptionId` 도 같은 도메인(`"1"`~`"5"`)이어야 한다.

### 2-4. SHORT_ANSWER

```jsonc
"shortAnswerProblems": [
  {
    "id": 5301,
    "questionContent": "Spring Boot 의 자동 설정 원리를 설명하시오."
    // bestAnswer / evaluationCriteria / relatedKeywords 미포함
  }
]
```

### 2-5. DEBATE (특이사항)

현재 백엔드 생성 흐름이 DEBATE 토픽을 채우지 않는다 → **`debateTopics: []` 가 항상 내려간다.** 실제 토론은 `/api/exams/debate/start` 가 담당한다.

```jsonc
"debateTopics": []
```

> FE 권장: 시험 카드의 `examType === "DEBATE"` 인 경우 본 API 를 호출하지 말고 곧장 `/api/exams/debate/start` 로 진입.

---

## 3) 응시 흐름 — 변경 없음 + 강조 사항

### 3-1. 응시 요청 — `POST /api/exams/submission`

기존 그대로. 요청 바디 예시:

```jsonc
{
  "examSessionId": 101,
  "answers": [
    { "questionId": 5201, "selectedOptionId": "3" },
    { "questionId": 5202, "selectedOptionId": "1" }
    // ... 받은 문제 수만큼
  ]
}
```

> **반드시 본 조회 응답(`fiveChoiceProblems[]` / `oxProblems[]` / …)의 순서대로 `answers` 배열을 구성해야 한다.** 현재 서버 검증은 `answers.size() == questions.size()` 만 비교한다. 순서가 어긋나면 채점 결과가 잘못 매겨질 수 있다. ID 기반 매칭은 별도 라운드에서 작업 예정.

`questionId` 는 본 조회 응답의 각 문제 객체의 `id` 필드(즉 `ExamQuestion.id`).

FIVE_CHOICE 의 `selectedOptionId` 는 `options[].id` 와 동일한 문자열(`"1"`~`"5"`).

### 3-2. 결과 조회 — `GET /api/exams/submission/{examResultId}`

기존 그대로. 본 작업에서 변경 없음.

---

## 4) FE 마이그레이션 체크리스트

- [ ] 강의실 콘텐츠 목록(`GET /api/courses/{courseId}/contents`)에서 시험 카드 클릭 시 **TEACHER 경로(`/api/exams/generation/{id}`)를 호출하던 분기가 있으면 STUDENT 분기는 `/api/exams/student/{id}` 로 분리**.
- [ ] DEBATE 카드 클릭 분기: 본 API 호출 없이 `/api/exams/debate/start` 직행.
- [ ] 응시 화면에서 `answers` 배열을 **반드시 본 조회 응답 순서대로** 구성. 사용자 답안을 문제 ID 별 맵으로 보관하다 보내려면, 보낼 때 조회 응답의 문제 배열 순서를 그대로 따라가도록 정렬 로직 추가.
- [ ] FIVE_CHOICE 의 `selectedOptionId` 는 `options[].id` 문자열(`"1"`~`"5"`) 사용. 숫자 또는 ChoiceOption.id 를 보내지 말 것.
- [ ] 시험 상태가 `READY` 가 아닌 경우(`INVALID_PHASE`) 사용자 메시지 처리. 예: "시험이 아직 준비 중입니다."

---

## 5) 백엔드 변경 요약 (참고)

FE 동작에 직접 영향은 없지만 알아두면 좋은 변경:

- **데이터 일관성**: 시험 생성 시 `exam_questions` 테이블에도 행이 만들어진다. 기존에는 `exam_sessions.exam_content_json` 에만 있었고, 응시 API 가 `exam_questions` 를 읽어 항상 "문제를 찾을 수 없습니다" 로 실패하던 결함을 해소.
- **마이그레이션**: `V5__exam_questions_assessment_nullable.sql` — `exam_questions.assessment_id` 를 nullable 로 완화. v2 흐름(ExamSession 기반)에는 Assessment 가 없기 때문.
- **TEACHER API 변경 없음**: 기존 `GET /api/exams/generation/{examSessionId}` 응답 스키마는 그대로 유지(정답 포함).
