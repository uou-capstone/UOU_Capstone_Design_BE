# 강의실 학생 리포트 API — FE 연동 가이드

선생님 전용. 자신이 개설한 강의실에 소속된 학생들의 학습 성과·역량을 조회하는 읽기 전용 리포트.

- 인증: Bearer JWT (TEACHER 권한)
- 1차 릴리스 범위: 별도 저장 테이블 없이 조회 시점 집계
- 모든 점수 필드는 **백분율(0~100)** 통일. 소수 첫째 자리까지
- 목록은 페이지 단위 응답(`PageResponse<StudentReportListItem>`). `?page=&size=&sort=&q=&status=` 쿼리 지원. size 최대 100

---

## 1. 학생 리포트 리스트

### `GET /api/courses/{courseId}/reports/students`

강의실에 소속된 학생들의 요약 항목 페이지를 반환. 응답은 공통 `PageResponse` shape.

#### Path
| 이름 | 타입 | 설명 |
|---|---|---|
| `courseId` | Long | 강의실 ID. 본인이 소유한 강의실이어야 함 |

#### Query
| 이름 | 타입 | 기본 | 허용값 | 설명 |
|---|---|---|---|---|
| `q` | string | — | (자유 입력) | 학생 이름 부분 일치 검색 (대소문자 무시) |
| `status` | string | `all` | `all` / `excelling` / `on_track` / `needs_attention` / `insufficient_data` | 학생 상태 필터 |
| `page` | int | `0` | 0 이상 | 페이지 번호 (0-based) |
| `size` | int | `20` | 1 ~ 100 | 페이지 크기. 100 초과 시 400 |
| `sort` | string | `name,asc` | `name,asc/desc` / `averageScore,asc/desc` / `latestActivity,asc/desc` / `reportStatus,asc/desc` | 정렬 — `field,direction` 형식. 다중 sort 파라미터 가능 |

> `sort=reportStatus,asc` 시 우선순위: `needs_attention` → `insufficient_data` → `on_track` → `excelling`. `desc` 는 반대.

#### Response 200
```json
{
  "content": [
    {
      "studentId": 101,
      "userId": 8001,
      "studentName": "홍길동",
      "averageScorePercent": 86.7,
      "examAttemptCount": 4,
      "submissionCount": 2,
      "latestActivityAt": "2026-04-25T18:42:00",
      "reportStatus": "on_track",
      "topStrengthLabel": "개념 이해",
      "topImprovementLabel": null
    },
    {
      "studentId": 104,
      "userId": 8044,
      "studentName": "김미정",
      "examAttemptCount": 0,
      "submissionCount": 0,
      "reportStatus": "insufficient_data"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

`null` 필드는 응답에서 생략될 수 있음 (Jackson `NON_NULL`). FE는 미존재로 간주.

#### `content[]` (`StudentReportListItem`) 필드
| 이름 | 타입 | 설명 |
|---|---|---|
| `studentId` | Long | 학생 PK. 상세 API 에서 사용 |
| `userId` | Long | User PK (호환용) |
| `studentName` | string | 표시 이름 |
| `averageScorePercent` | number? | 평균 점수(%) — 시험 결과 0건이면 `null` |
| `examAttemptCount` | int | 강의실 내 응시 시험 수 |
| `submissionCount` | int | 강의실 내 v1 과제 제출 수 |
| `latestActivityAt` | datetime? | 시험·제출 중 가장 최근 시각 |
| `reportStatus` | string | 아래 표 참조 |
| `topStrengthLabel` | string? | 가장 강한 역량 라벨 — 없으면 `null` |
| `topImprovementLabel` | string? | 가장 약한 역량 라벨 — 없으면 `null` |

---

## 2. 학생 상세 리포트

### `GET /api/courses/{courseId}/reports/students/{studentId}`

#### Path
| 이름 | 타입 | 설명 |
|---|---|---|
| `courseId` | Long | 강의실 ID |
| `studentId` | Long | 강의실에 등록된 학생 PK |

#### Response 200
```json
{
  "student": {
    "studentId": 101,
    "userId": 8001,
    "studentName": "홍길동",
    "email": "hong@example.com"
  },
  "course": {
    "courseId": 12,
    "title": "고등 미적분 1반"
  },
  "activitySummary": {
    "examAttemptCount": 4,
    "submissionCount": 2,
    "latestActivityAt": "2026-04-25T18:42:00"
  },
  "scoreSummary": {
    "averageScorePercent": 86.7,
    "highestScorePercent": 95.0,
    "lowestScorePercent": 72.5,
    "recentTrendPercent": [88.0, 90.0, 82.0]
  },
  "competencies": [
    {
      "key": "concept",
      "label": "개념 이해",
      "averageScorePercent": 92.5,
      "evidenceCount": 6,
      "latestFeedback": "핵심 정의를 정확히 적용함",
      "status": "strong"
    },
    {
      "key": "application",
      "label": "응용 추론",
      "averageScorePercent": 65.0,
      "evidenceCount": 4,
      "latestFeedback": "조건부 명제 응용 시 누락이 있음",
      "status": "needs_improvement"
    }
  ],
  "submissionSummary": {
    "submittedCount": 2,
    "gradedCount": 1,
    "pendingCount": 1,
    "missingCount": 1
  },
  "evidence": [
    {
      "type": "exam",
      "examResultId": 5012,
      "examType": "FIVE_CHOICE",
      "lectureTitle": "3주차: 미분 응용",
      "completedAt": "2026-04-25T18:42:00",
      "scorePercent": 88.0,
      "feedback": "응용 문제에서 조건 누락 패턴이 반복됨"
    },
    {
      "type": "submission",
      "submissionId": 311,
      "assessmentTitle": "2주차 형성평가",
      "submittedAt": "2026-04-22T10:11:00",
      "status": "SUBMITTED"
    }
  ],
  "narrativeReport": {
    "summary": "평균 86.7점, 일부 역량에서 보강이 필요합니다.",
    "strengths": [
      "개념 이해 평균 92.5점"
    ],
    "improvements": [
      "응용 추론 평균 65.0점 — 보강 필요"
    ],
    "nextSteps": [
      "응용 추론 영역의 기본 개념을 다시 확인하고 유사 문제를 추가 풀이해 보세요.",
      "강의별 핵심 개념 요약을 복습하고 짧은 OX/플래시카드로 빠르게 점검하세요."
    ]
  },
  "reportStatus": "needs_attention",
  "reportWarnings": []
}
```

#### 섹션별 의미

**student / course** — 식별 정보. `studentId`는 카드 클릭 → 상세 진입에 사용한 ID와 동일.

**activitySummary** — 강의실 단위 활동 카운트 + 최근 활동 시각.

**scoreSummary** — 시험 결과 기반 점수 요약.
- `averageScorePercent`/`highest`/`lowest`: 시험 결과가 0건이면 `null` (필드 자체 생략 가능)
- `recentTrendPercent`: 최신 → 과거 순. 회차 부족 시 더 짧은 배열. 시험 0건이면 빈 배열 `[]`

**competencies** — `userFeedbackJson.evaluationItems` 기반 역량별 평균. 평균 점수 내림차순. 데이터 없으면 빈 배열.

**submissionSummary** — v1 `Assessment`/`Submission` 기준 보조 지표.
- `submittedCount`: 학생이 제출한 Submission 수
- `gradedCount`: 그 중 `examResult` 연결되었거나 `status=GRADED` 인 수
- `pendingCount`: 제출 후 채점 대기
- `missingCount`: 강의실 Assessment 총수 − 학생 제출 수 (음수 방지)

**evidence** — 시험 결과 + 제출 기록을 시간 역순으로 합쳐 최대 10건.
- `type: "exam"` 항목은 `examResultId`/`examType`/`lectureTitle`/`completedAt`/`scorePercent`/`feedback` 사용
- `type: "submission"` 항목은 `submissionId`/`assessmentTitle`/`submittedAt`/`status` 사용

**narrativeReport** — 서버 템플릿 기반 한글 요약.
- `summary`: 1줄
- `strengths`: STRONG 역량 최대 2개 (없으면 `[]`)
- `improvements`: NEEDS_IMPROVEMENT 역량 최대 2개 (없으면 `[]`)
- `nextSteps`: 권장 액션 최대 2개

**reportStatus** — 학생의 종합 상태 (리스트 카드의 `reportStatus` 와 동일 규칙).

**reportWarnings** — 데이터 결손 신호 배열. FE는 표시 여부 결정.
- `feedback_profile_missing`: `userFeedbackJson` 자체가 비어 있는 시험 결과 존재
- `feedback_profile_invalid`: `userFeedbackJson.evaluationItems` 형식이 잘못됨

---

## 3. 상태/등급 값 정리

### `reportStatus` (학생 종합)

| 값 | 의미 | 조건 |
|---|---|---|
| `excelling` | 우수 | 평균 ≥ 90 **AND** 모든 역량 평균 ≥ 80 |
| `on_track` | 정상 | 위/아래 어느 그룹에도 속하지 않음 |
| `needs_attention` | 주의 | 평균 < 70 **OR** 한 역량이라도 `needs_improvement` |
| `insufficient_data` | 데이터 부족 | 시험 응시 0건 |

### `competencies[].status`

| 값 | 조건 |
|---|---|
| `strong` | 평균 ≥ 85 |
| `watch` | 70 ≤ 평균 < 85 |
| `needs_improvement` | 평균 < 70 |
| `insufficient_data` | 근거 1건 미만 |

### `evidence[].type`

`exam` / `submission` 두 종류. FE에서 분기 렌더.

### `evidence[].status` (submission 전용)

`SUBMITTED` (제출 완료, 미채점) / `GRADED` (채점 완료). 현재 v1 흐름은 거의 `SUBMITTED` 상태.

### `evidence[].examType` (exam 전용)

`FLASH_CARD` / `OX_PROBLEM` / `FIVE_CHOICE` / `SHORT_ANSWER` / `DEBATE`

---

## 4. 에러 응답

표준 `BusinessException` 포맷.

| HTTP | code | 발생 조건 |
|---|---|---|
| 401 | `4010` | 미인증 / 토큰 만료 |
| 403 | `4030` | 강의실 소유 선생님이 아님, 또는 STUDENT 가 호출 |
| 404 | `4041` | 학생이 해당 강의실에 등록되어 있지 않음 (`studentId` 잘못됨) |
| 404 | `4042` | 강의실 미존재 (`courseId` 잘못됨) |
| 400 | `4000` | `size > 100`, 비허용 sort 필드, `page < 0` 등 페이징 파라미터 위반 |

```json
{
  "code": "4030",
  "message": "접근 권한이 없습니다."
}
```

---

## 5. FE 구현 시 체크리스트

- [ ] 리스트 응답은 `PageResponse` shape (`content`/`page`/`size`/`totalElements`/`totalPages`/`first`/`last`). 1페이지 = `page=0`
- [ ] 페이지 이동 시 `?page=&size=` 쿼리만 갱신. 정렬은 `sort=field,direction` (직전 호출과 동일하게 유지)
- [ ] `size` 최대 100. UI 에서 100 초과 입력 차단
- [ ] 항목의 `averageScorePercent`/`latestActivityAt`이 `null` 인 경우 "응시 기록 없음" 표시
- [ ] `reportStatus=insufficient_data` 항목은 회색·라벨 별도 처리
- [ ] 정렬은 서버에서 처리 — 클라이언트 재정렬 X (서버 응답 순서 그대로 표시)
- [ ] 검색 `q` 변경 시 디바운스 후 재호출 (200~300ms 권장). `q` 변경 시 `page=0` 으로 리셋
- [ ] 상세 진입 시 `studentId` (항목의 `studentId`) 사용. `userId` 아님
- [ ] `competencies` 가 빈 배열인 경우 "역량 분석 데이터 부족" 안내
- [ ] `evidence` 의 timestamp는 `completedAt` (exam) / `submittedAt` (submission) — 통합 타임라인 정렬 시 둘 중 존재하는 값 사용
- [ ] `reportWarnings` 에 `feedback_profile_*` 이 있으면 "일부 결과의 역량 데이터가 누락되어 분석 정확도가 떨어질 수 있음" 경고 표시
- [ ] `narrativeReport` 의 4 필드(`summary`/`strengths`/`improvements`/`nextSteps`) 는 항상 존재. `strengths`/`improvements` 는 빈 배열 가능

---

## 6. 1차 릴리스에서 제외된 것

PDF 내보내기 / 비교 대시보드 / AI 재분석 스트리밍 / 학생 본인 조회 / 다중 강의실 통합 비교 / DB-level 페이징 (현재는 in-memory slice — 학생 N=수십~수백 가정) — 모두 추후 별도 티켓.
