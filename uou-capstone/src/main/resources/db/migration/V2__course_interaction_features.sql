-- ============================================================
-- V2__course_interaction_features.sql
--
-- 신규 강의실 상호작용 기능: 공지사항 / 토론게시판 / 출석.
-- 6개 테이블 일괄 생성.
--
-- 주의:
--   - 테스트 환경(H2 MODE=MySQL, ddl-auto=create-drop, flyway.enabled=false)에서는
--     이 SQL 이 실행되지 않고 Hibernate 가 엔티티 매핑으로 스키마를 생성한다.
--     운영(MySQL) 부팅 시 ddl-auto=validate 가 이 DDL 과 엔티티 매핑이 일치하는지 검증.
--   - parent_comment_id FK 의 ON DELETE CASCADE 는 부모 댓글 삭제 시 답글까지 함께 삭제.
--     엔티티에서는 @OnDelete(action = CASCADE) 로 동일 동작을 H2 테스트 환경에도 적용.
--   - attendance_sessions.lecture_id ON DELETE SET NULL —
--     lecture 삭제 시 해당 lecture 와 연결되어 있던 출석 세션은 그대로 보존되고 lecture_id 만 NULL.
-- ============================================================

-- ============= notices =============
CREATE TABLE `notices` (
  `notice_id`         bigint NOT NULL AUTO_INCREMENT,
  `course_id`         bigint NOT NULL,
  `author_teacher_id` bigint NOT NULL,
  `title`             varchar(100) NOT NULL,
  `content_markdown`  text NOT NULL,
  `category`          varchar(20) NOT NULL,
  `priority`          varchar(20) NOT NULL,
  `pinned`            bit(1) NOT NULL DEFAULT b'0',
  `created_at`        datetime(6) DEFAULT NULL,
  `updated_at`        datetime(6) DEFAULT NULL,
  PRIMARY KEY (`notice_id`),
  KEY `idx_notices_course_pinned_created` (`course_id`, `pinned` DESC, `created_at` DESC),
  CONSTRAINT `FK_notices_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_notices_author` FOREIGN KEY (`author_teacher_id`) REFERENCES `teachers` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `notice_comments` (
  `notice_comment_id` bigint NOT NULL AUTO_INCREMENT,
  `notice_id`         bigint NOT NULL,
  `author_user_id`    bigint NOT NULL,
  `parent_comment_id` bigint DEFAULT NULL,
  `content_markdown`  text NOT NULL,
  `created_at`        datetime(6) DEFAULT NULL,
  `updated_at`        datetime(6) DEFAULT NULL,
  PRIMARY KEY (`notice_comment_id`),
  KEY `idx_nc_notice` (`notice_id`, `created_at`),
  CONSTRAINT `FK_nc_notice` FOREIGN KEY (`notice_id`) REFERENCES `notices` (`notice_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_nc_author` FOREIGN KEY (`author_user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FK_nc_parent` FOREIGN KEY (`parent_comment_id`) REFERENCES `notice_comments` (`notice_comment_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============= discussions =============
CREATE TABLE `discussions` (
  `discussion_id`    bigint NOT NULL AUTO_INCREMENT,
  `course_id`        bigint NOT NULL,
  `author_user_id`   bigint NOT NULL,
  `title`            varchar(120) NOT NULL,
  `content_markdown` text NOT NULL,
  `category`         varchar(20) NOT NULL,
  `pinned`           bit(1) NOT NULL DEFAULT b'0',
  `allow_comments`   bit(1) NOT NULL DEFAULT b'1',
  `view_count`       int NOT NULL DEFAULT 0,
  `created_at`       datetime(6) DEFAULT NULL,
  `updated_at`       datetime(6) DEFAULT NULL,
  PRIMARY KEY (`discussion_id`),
  KEY `idx_disc_course_pinned_created` (`course_id`, `pinned` DESC, `created_at` DESC),
  CONSTRAINT `FK_disc_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_disc_author` FOREIGN KEY (`author_user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `discussion_comments` (
  `discussion_comment_id` bigint NOT NULL AUTO_INCREMENT,
  `discussion_id`         bigint NOT NULL,
  `author_user_id`        bigint NOT NULL,
  `parent_comment_id`     bigint DEFAULT NULL,
  `content_markdown`      text NOT NULL,
  `created_at`            datetime(6) DEFAULT NULL,
  `updated_at`            datetime(6) DEFAULT NULL,
  PRIMARY KEY (`discussion_comment_id`),
  KEY `idx_dc_disc` (`discussion_id`, `created_at`),
  CONSTRAINT `FK_dc_disc` FOREIGN KEY (`discussion_id`) REFERENCES `discussions` (`discussion_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_dc_user` FOREIGN KEY (`author_user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FK_dc_parent` FOREIGN KEY (`parent_comment_id`) REFERENCES `discussion_comments` (`discussion_comment_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============= attendance =============
CREATE TABLE `attendance_sessions` (
  `attendance_session_id`  bigint NOT NULL AUTO_INCREMENT,
  `course_id`              bigint NOT NULL,
  `lecture_id`             bigint DEFAULT NULL,
  `title`                  varchar(100) NOT NULL,
  `session_date`           date NOT NULL,
  `start_time`             time DEFAULT NULL,
  `end_time`               time DEFAULT NULL,
  `created_by_teacher_id`  bigint NOT NULL,
  `created_at`             datetime(6) DEFAULT NULL,
  `updated_at`             datetime(6) DEFAULT NULL,
  PRIMARY KEY (`attendance_session_id`),
  KEY `idx_as_course_date` (`course_id`, `session_date` DESC),
  CONSTRAINT `FK_as_course`     FOREIGN KEY (`course_id`)  REFERENCES `courses` (`course_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_as_lecture`    FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`) ON DELETE SET NULL,
  CONSTRAINT `FK_as_created_by` FOREIGN KEY (`created_by_teacher_id`) REFERENCES `teachers` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `attendance_records` (
  `attendance_record_id`   bigint NOT NULL AUTO_INCREMENT,
  `attendance_session_id`  bigint NOT NULL,
  `student_id`             bigint NOT NULL,
  `status`                 varchar(20) NOT NULL,
  `marked_at`              datetime(6) DEFAULT NULL,
  `marked_by_teacher_id`   bigint NOT NULL,
  `note`                   varchar(255) DEFAULT NULL,
  `created_at`             datetime(6) DEFAULT NULL,
  `updated_at`             datetime(6) DEFAULT NULL,
  PRIMARY KEY (`attendance_record_id`),
  UNIQUE KEY `uq_ar_session_student` (`attendance_session_id`, `student_id`),
  CONSTRAINT `FK_ar_session`   FOREIGN KEY (`attendance_session_id`) REFERENCES `attendance_sessions` (`attendance_session_id`) ON DELETE CASCADE,
  CONSTRAINT `FK_ar_student`   FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`),
  CONSTRAINT `FK_ar_marked_by` FOREIGN KEY (`marked_by_teacher_id`) REFERENCES `teachers` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
