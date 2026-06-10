-- ============================================================
-- V12__student_report_analysis.sql
--
-- Student AI report analysis storage.
-- One latest analysis row per (course, student).
-- ============================================================

CREATE TABLE `student_report_analyses` (
  `student_report_analysis_id` bigint NOT NULL AUTO_INCREMENT,
  `course_id`                  bigint NOT NULL,
  `student_id`                 bigint NOT NULL,
  `analysis_json`              longtext NOT NULL,
  `summary_markdown`           longtext,
  `source`                     varchar(100) DEFAULT NULL,
  `fallback_used`              bit(1) NOT NULL DEFAULT b'0',
  `fallback_reason`            varchar(255) DEFAULT NULL,
  `confidence`                 varchar(10) DEFAULT NULL,
  `generated_at`               datetime(6) DEFAULT NULL,
  `created_at`                 datetime(6) DEFAULT NULL,
  `updated_at`                 datetime(6) DEFAULT NULL,
  PRIMARY KEY (`student_report_analysis_id`),
  UNIQUE KEY `uq_sra_course_student` (`course_id`, `student_id`),
  CONSTRAINT `FK_sra_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_sra_student` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
