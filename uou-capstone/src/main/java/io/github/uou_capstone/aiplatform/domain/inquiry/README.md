# inquiry 도메인

학생이 강의 중 제시된 **AI 질문 콘텐츠**(`GeneratedContent` with `aiQuestionId`)에 답변하면, FastAPI QA 엔진이 평가하고 보충 설명을 반환. 응답이 `StudentInquiry` 로 영속.
이 도메인 작업 시 참고용 상시 메모.

---

## 개요

- **StudentInquiry 엔티티**: `student` + `lecture` + `inquiryText` (학생 답변) + `agentAnswer` (AI 평가/설명) — 학생 한 명이 강의에 여러 inquiry 보유 가능
- 패키지 구조가 다른 도메인과 달리 **flat** — `controller/`, `service/` 없이 도메인 루트에 클래스 직배치 (`InquiryController`, `InquiryService`, `StudentInquiry`, `StudentInquiryRepository`)
- 역할: **STUDENT 만** 사용 (`@PreAuthorize("hasAuthority('STUDENT')")`)

---

## 주요 플로우

prefix: `/api/inquiries`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST /answer` | STUDENT | AI 질문에 답변 → FastAPI QA 평가 → 결과 + 보충 설명 반환 |

> 향후 추가 예정 (현재 주석): `POST /ask-random` — 학생 임의 질문, `POST /ask` — 강의 내용 자유 질문

### 답변 평가 흐름 (`answerAiQuestion`)

1. `aiQuestionId` 로 원본 질문 콘텐츠(`GeneratedContent`) 조회 → 강의 추출
2. 수강생 권한 체크 (`enrollmentRepository.existsByStudentAndCourse`)
3. **평가 기준 PDF 결정** (`resolveQaPdfMaterial`) — 아래 Gotcha 참조
4. FastAPI 호출: `fastApiQaClient.evaluate({ original_q, user_answer, pdf_path })` → `/api/v2/qa/evaluate`
5. 응답 정규화:
   - `status` 비어있으면 `"GOOD"` 으로 폴백 (v2.6 호환 — score/feedback/model_answer 형식)
   - `explanation` 비어있으면 `model_answer` → `feedback` → 기본 메시지 순으로 폴백
6. DB 저장:
   - `GOOD` → `agentAnswer = explanation`
   - `BAD` → `agentAnswer = JSON 직렬화된 steps` (하위 개념 학습 단계)
7. 응답 DTO: `(status, explanation, steps)`

---

## PDF 평가 기준 결정 규칙 (`resolveQaPdfMaterial`)

평가 정확도를 위해 어떤 PDF 를 기준으로 채점할지 명시 필요. 우선순위:

1. **요청 body 의 `materialId`** — 가장 강한 신호. 강의 일치 + PDF 타입 검증
2. **`questionContent.materialReferences` JSON 에서 파싱** — `{"materialId":N}` 또는 `{"material_id":N}` 또는 단일 숫자 문자열
3. **강의에 PDF 가 정확히 1개일 때 자동 선택** — 모호성 없을 때만
4. **PDF 가 2개 이상이고 `materialId` 미지정** → `INVALID_PARAMETER` 에러

> 다중 PDF 강의 클라이언트는 항상 `materialId` 명시 권장.

---

## 외부 의존

- **FastAPI QA**: `POST /api/v2/qa/evaluate`
  - 클라이언트: `integration/fastapi/FastApiQaClient.evaluate(qaRequest)`
  - 요청: `{ original_q, user_answer, pdf_path }`
  - 응답: `AiQaResponseDto { status, explanation, steps }` 또는 v2.6 형식 (score/feedback/model_answer)

---

## 상시 주의사항 (Gotcha)

### v2.6 응답 호환성
이전 FastAPI 버전(v2.6)은 `status` 가 없고 `score/feedback/model_answer` 만 반환. 신규 코드 추가 시 **두 형식 모두 처리** 하는 폴백 로직 유지. FastAPI(`llm_multi_agent`) 가 v3 로 통일되면 폴백 제거 가능.

### `agentAnswer` 저장 형식 — GOOD/BAD 분기
- GOOD: 평문 explanation
- BAD: JSON 문자열 (`steps` 배열) — 읽을 때 `objectMapper.readValue(...)` 필요

검색·표시 로직 작성 시 GOOD/BAD 모두 고려. 일관성 있는 조회 API 가 필요하면 별도 status 컬럼 추가 권장 (현재는 컬럼 없음 — 페이로드만 보고 판별).

### `materialReferences` 는 자유 형식 JSON 또는 정수 문자열
`GeneratedContent.materialReferences` 는 단순 `Long` 문자열일 수도, JSON 객체일 수도 있음. 파싱 실패 시 조용히 `null` 반환 후 다음 단계로 폴백 — 새 형식 추가 시 `tryParseMaterialIdFromReferences` 보강.

### 권한 체크 — Course 단위
`enrollmentRepository.existsByStudentAndCourse(student, lecture.getCourse())` — 강의가 아닌 **강의실** 수강 여부. inquiry 가 강의별이지만 권한은 강의실 수준.

### Repository 가 비어 있음
`StudentInquiryRepository extends JpaRepository<StudentInquiry, Integer>` — 커스텀 메서드 0. 학생별 inquiry 목록 조회 등이 필요하면 추가 (현재 호출 경로 없음).

> ⚠️ ID 타입이 **`Integer`** 로 되어 있으나 엔티티의 `id` 는 `Long`. JPA 가 동작하긴 하지만 타입 불일치 — 향후 정리 필요. 영향: 직접 `findById(Integer)` 호출 시 타입 변환 발생.

### Cascade
- `Student.studentInquiries` (OneToMany, cascade=ALL, orphanRemoval=true) — 학생 삭제 시 자동 삭제
- `Lecture.studentInquiries` (OneToMany, cascade=ALL, orphanRemoval=true) — 강의 삭제 시 자동 삭제
- 강의실 삭제 흐름은 `course.lecture` cascade 로 흘러 자동 정리 — 별도 `deleteByLectureId` 불필요

### 동시성
같은 학생이 같은 `aiQuestionId` 에 두 번 답변하면 두 row 가 생김 (유니크 제약 없음). 의도된 — 재답변 가능. UI 가 마지막 row 만 보여주는 식으로 처리.

---

## 주요 파일 (flat 구조)

- `InquiryController.java` — `POST /api/inquiries/answer`
- `InquiryService.java` — FastAPI QA 호출 + PDF 결정 + 결과 영속
- `StudentInquiry.java` — 엔티티
- `StudentInquiryRepository.java` — 빈 repository

### DTO (`dto/`)
- `dto/InquiryRequestDto.java` — `aiQuestionId`, `answerText`, `materialId` (선택)
- `dto/InquiryResponseDto.java` — `status`, `explanation`, `steps`
- `dto/AiQaRequestDto.java`, `AiQaResponseDto.java` — FastAPI 요청/응답 매핑

### 의존 (다른 도메인)
- `course.lecture.entity.GeneratedContent`, `course.lecture.repository.GeneratedContentRepository` — `findByAiQuestionId`
- `course.lecture.entity.Lecture` — 강의 추출
- `course.repository.EnrollmentRepository` — 권한 체크
- `material.entity.Material`, `material.repository.MaterialRepository` — PDF 결정
- `user.entity.Student` — 식별자
- `integration/fastapi/FastApiQaClient` — `/api/v2/qa/evaluate`
