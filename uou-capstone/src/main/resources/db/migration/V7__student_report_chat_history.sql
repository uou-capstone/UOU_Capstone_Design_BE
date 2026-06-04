CREATE TABLE `student_report_chat_sessions` (
  `report_chat_session_id` bigint NOT NULL AUTO_INCREMENT,
  `course_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `last_message_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`report_chat_session_id`),
  KEY `idx_srcs_course_student` (`course_id`, `student_id`),
  KEY `idx_srcs_last_message` (`last_message_at`),
  CONSTRAINT `fk_srcs_course` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_srcs_student` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `student_report_chat_messages` (
  `report_chat_message_id` bigint NOT NULL AUTO_INCREMENT,
  `report_chat_session_id` bigint NOT NULL,
  `role` varchar(20) NOT NULL,
  `content` text NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`report_chat_message_id`),
  KEY `idx_srcm_session_created` (`report_chat_session_id`, `created_at`),
  CONSTRAINT `fk_srcm_session` FOREIGN KEY (`report_chat_session_id`)
    REFERENCES `student_report_chat_sessions` (`report_chat_session_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
