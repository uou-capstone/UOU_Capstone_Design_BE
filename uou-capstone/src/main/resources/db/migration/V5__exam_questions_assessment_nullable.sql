-- ============================================================
-- V5__exam_questions_assessment_nullable.sql
--
-- 목적: exam_questions.assessment_id 를 nullable 로 완화.
--
-- 배경:
--   v2 시험 생성 흐름은 ExamSession 기반이며, 1:1 대응되는 Assessment 가 없다.
--   기존 NOT NULL 제약으로는 ExamGenerationService 에서 생성된 문제를 ExamQuestion
--   행으로 hydrate 할 수 없어, 응시(ExamSubmissionService) 가 항상 "문제를 찾을 수
--   없습니다" 로 실패하던 결함을 해소한다.
--
-- 영향:
--   - FK 제약(FK1bvhq9ndv82xqxkqx7mi6fhla → assessments.assessment_id)은 유지.
--   - 값이 들어오면 여전히 참조 무결성 검사 적용.
--   - v1(Assessment 기반) 행은 그대로 NOT NULL 시절의 값을 유지하므로 영향 없음.
-- ============================================================

ALTER TABLE `exam_questions`
    MODIFY COLUMN `assessment_id` bigint NULL;
