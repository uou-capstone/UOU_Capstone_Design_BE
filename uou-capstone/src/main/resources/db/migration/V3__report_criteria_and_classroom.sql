-- ============================================================
-- V3__report_criteria_and_classroom.sql
--
-- MergeEdu AI 기능 신규 2종을 위한 테이블:
--   1) course_report_criteria  — 교사가 정의한 강의실 평가 기준 (AI 추천 보조 대상)
--   2) classroom_reports        — 강의실 종합 리포트 (AI 분석 결과 저장, 1 course = 1 row)
-- ============================================================

-- ============= course_report_criteria =============
CREATE TABLE `course_report_criteria` (
  `criterion_id`   bigint NOT NULL AUTO_INCREMENT,
  `course_id`      bigint NOT NULL,
  `label`          varchar(100) NOT NULL,
  `description`    varchar(500) DEFAULT NULL,
  `weight`         int NOT NULL DEFAULT 0,
  `created_at`     datetime(6) DEFAULT NULL,
  `updated_at`     datetime(6) DEFAULT NULL,
  PRIMARY KEY (`criterion_id`),
  KEY `idx_crc_course` (`course_id`),
  CONSTRAINT `FK_crc_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============= classroom_reports =============
-- 한 강의실당 1행 (UPSERT). highlights/risks/coachingPriorities 는 AI 가 배열로 반환하므로 JSON 문자열로 보관.
CREATE TABLE `classroom_reports` (
  `classroom_report_id`        bigint NOT NULL AUTO_INCREMENT,
  `course_id`                  bigint NOT NULL,
  `summary_markdown`           longtext,
  `highlights_json`            longtext,
  `risks_json`                 longtext,
  `coaching_priorities_json`   longtext,
  `source`                     varchar(20) DEFAULT NULL,
  `fallback_used`              bit(1) NOT NULL DEFAULT b'0',
  `fallback_reason`            varchar(255) DEFAULT NULL,
  `confidence`                 varchar(10) DEFAULT NULL,
  `generated_at`               datetime(6) DEFAULT NULL,
  `created_at`                 datetime(6) DEFAULT NULL,
  `updated_at`                 datetime(6) DEFAULT NULL,
  PRIMARY KEY (`classroom_report_id`),
  UNIQUE KEY `uq_clr_course` (`course_id`),
  CONSTRAINT `FK_clr_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
