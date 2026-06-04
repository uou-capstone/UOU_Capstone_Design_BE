CREATE TABLE `learning_chat_sessions` (
  `chat_session_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `last_message_at` datetime(6) DEFAULT NULL,
  `title` varchar(100) DEFAULT NULL,
  `lecture_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`chat_session_id`),
  KEY `idx_learning_chat_session_user_lecture` (`user_id`, `lecture_id`),
  KEY `idx_learning_chat_session_last_message` (`last_message_at`),
  KEY `fk_learning_chat_session_lecture` (`lecture_id`),
  CONSTRAINT `fk_learning_chat_session_lecture` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`),
  CONSTRAINT `fk_learning_chat_session_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `learning_chat_messages` (
  `message_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `content` text NOT NULL,
  `page_number` int DEFAULT NULL,
  `role` varchar(20) NOT NULL,
  `chat_session_id` bigint NOT NULL,
  PRIMARY KEY (`message_id`),
  KEY `idx_learning_chat_message_session_created` (`chat_session_id`, `created_at`),
  CONSTRAINT `fk_learning_chat_message_session` FOREIGN KEY (`chat_session_id`) REFERENCES `learning_chat_sessions` (`chat_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
