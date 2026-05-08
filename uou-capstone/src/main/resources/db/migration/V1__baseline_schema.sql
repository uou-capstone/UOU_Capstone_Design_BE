-- ============================================================
-- V1__baseline_schema.sql
--
-- 출처: develop EC2 (dev-mysql 컨테이너) `mysqldump --no-data` 결과.
-- 정리: AUTO_INCREMENT=<숫자> 절 제거(환경별 노이즈), 그 외 dump 원문 유지.
-- 매칭: 도메인 entity 20개 ↔ DB 테이블 20개 1:1 확인 완료.
--
-- 동작 (application-{prod,local}.yml 의 spring.flyway 설정 기준):
--   - 기존 데이터 있는 DB(develop/prod, 사용 중인 로컬 DB)
--       baseline-on-migrate=true + baseline-version=1 에 의해
--       이 V1 은 *실행되지 않고* flyway_schema_history 에 type=BASELINE 으로 기록.
--   - 빈 DB(신규 로컬, 신규 환경)
--       이 V1 이 실제 실행되어 모든 테이블 생성. ddl-auto=validate 가 통과해야 함.
--
-- 테스트 환경(H2 MODE=MySQL)에서는 spring.flyway.enabled=false 로 이 파일이 실행되지 않음.
--
-- ⚠️  CRITICAL — develop/prod 첫 배포 이후 이 파일은 *절대 수정 금지*.
--    Flyway checksum 이 깨지면 이후 모든 마이그레이션이 차단됨.
--    스키마 변경은 V2__xxx.sql, V3__xxx.sql 로 추가할 것.
-- ============================================================

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `assessments` (
  `assessment_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `ai_generated_status` enum('COMPLETED','FAILED','PENDING','PROCESSING') NOT NULL,
  `due_date` datetime(6) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  `assessment_type` enum('ASSIGNMENT','QUIZ') NOT NULL,
  `course_id` bigint NOT NULL,
  `exam_session_id` bigint DEFAULT NULL,
  PRIMARY KEY (`assessment_id`),
  KEY `FKa2nh608bmj0k0wjf0rw7oiha5` (`course_id`),
  KEY `FKc5fpofh70pqmgv1kmngg3t69o` (`exam_session_id`),
  CONSTRAINT `FKa2nh608bmj0k0wjf0rw7oiha5` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`),
  CONSTRAINT `FKc5fpofh70pqmgv1kmngg3t69o` FOREIGN KEY (`exam_session_id`) REFERENCES `exam_sessions` (`exam_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `choice_options` (
  `choice_option_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `is_correct` bit(1) NOT NULL,
  `choice_option_text` tinytext NOT NULL,
  `exam_question_id` bigint NOT NULL,
  PRIMARY KEY (`choice_option_id`),
  KEY `FKo0m1dx7cag74vgrrwgcrwx76y` (`exam_question_id`),
  CONSTRAINT `FKo0m1dx7cag74vgrrwgcrwx76y` FOREIGN KEY (`exam_question_id`) REFERENCES `exam_questions` (`exam_question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `course_join_requests` (
  `join_request_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `status` enum('APPROVED','BLOCKED','PENDING','REJECTED') NOT NULL,
  `course_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  PRIMARY KEY (`join_request_id`),
  KEY `idx_join_request_course_status` (`course_id`,`status`),
  KEY `idx_join_request_student_course` (`student_id`,`course_id`),
  CONSTRAINT `FKgji7dccw6hikap7kj586yt03` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`),
  CONSTRAINT `FKmrmxf1yslsx5l5wn6opqi5tts` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `courses` (
  `course_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `description` longtext,
  `invitation_code` varchar(255) NOT NULL,
  `title` varchar(255) NOT NULL,
  `teacher_id` bigint NOT NULL,
  PRIMARY KEY (`course_id`),
  UNIQUE KEY `UKlcwnkvjblipynpwiq3ljtu95e` (`invitation_code`),
  KEY `FK468oyt88pgk2a0cxrvxygadqg` (`teacher_id`),
  CONSTRAINT `FK468oyt88pgk2a0cxrvxygadqg` FOREIGN KEY (`teacher_id`) REFERENCES `teachers` (`teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `enrollments` (
  `enrollment_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `status` enum('ACTIVE','COMPLETED','DROPPED') NOT NULL,
  `course_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  PRIMARY KEY (`enrollment_id`),
  UNIQUE KEY `enrollment_uk` (`student_id`,`course_id`),
  KEY `FKho8mcicp4196ebpltdn9wl6co` (`course_id`),
  CONSTRAINT `FK8kf1u1857xgo56xbfmnif2c51` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`),
  CONSTRAINT `FKho8mcicp4196ebpltdn9wl6co` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_profiles` (
  `profile_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `content_hash` varchar(64) DEFAULT NULL,
  `profile_json` json NOT NULL,
  `lecture_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`profile_id`),
  UNIQUE KEY `uk_user_lecture` (`user_id`,`lecture_id`),
  KEY `FK1h8rvow50aal06j6gxnt16s28` (`lecture_id`),
  CONSTRAINT `FK1h8rvow50aal06j6gxnt16s28` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`),
  CONSTRAINT `FKqjete5xcmhyw4l5lw6rje5x6d` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_questions` (
  `exam_question_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `created_by` enum('AI','TEACHER') NOT NULL,
  `exam_type` enum('DEBATE','FIVE_CHOICE','FLASH_CARD','OX_PROBLEM','SHORT_ANSWER') NOT NULL,
  `question_content` text NOT NULL,
  `question_metadata` json DEFAULT NULL,
  `question_order` int DEFAULT NULL,
  `assessment_id` bigint NOT NULL,
  `exam_session_id` bigint DEFAULT NULL,
  PRIMARY KEY (`exam_question_id`),
  KEY `FK1bvhq9ndv82xqxkqx7mi6fhla` (`assessment_id`),
  KEY `FKakqa2nntufij0v4cffy8taisl` (`exam_session_id`),
  CONSTRAINT `FK1bvhq9ndv82xqxkqx7mi6fhla` FOREIGN KEY (`assessment_id`) REFERENCES `assessments` (`assessment_id`),
  CONSTRAINT `FKakqa2nntufij0v4cffy8taisl` FOREIGN KEY (`exam_session_id`) REFERENCES `exam_sessions` (`exam_session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_results` (
  `exam_result_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `max_score` decimal(5,2) DEFAULT NULL,
  `overall_feedback` text,
  `total_score` decimal(5,2) DEFAULT NULL,
  `user_feedback_json` json DEFAULT NULL,
  `exam_session_id` bigint NOT NULL,
  `submission_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`exam_result_id`),
  KEY `FKdttcjlamyumdq51ryhyyypa8g` (`exam_session_id`),
  KEY `FKeejpb7aqxkfe84yxgnfm9g56r` (`submission_id`),
  KEY `FKt2jcn29o332cpiv7s7h3o877e` (`user_id`),
  CONSTRAINT `FKdttcjlamyumdq51ryhyyypa8g` FOREIGN KEY (`exam_session_id`) REFERENCES `exam_sessions` (`exam_session_id`),
  CONSTRAINT `FKeejpb7aqxkfe84yxgnfm9g56r` FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`submission_id`),
  CONSTRAINT `FKt2jcn29o332cpiv7s7h3o877e` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_sessions` (
  `exam_session_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `debate_history_json` json DEFAULT NULL,
  `debate_phase` enum('PHASE1','PHASE2','PHASE3') DEFAULT NULL,
  `display_name` varchar(255) DEFAULT NULL,
  `evaluation_log_json` json DEFAULT NULL,
  `exam_content_json` json DEFAULT NULL,
  `exam_type` enum('DEBATE','FIVE_CHOICE','FLASH_CARD','OX_PROBLEM','SHORT_ANSWER') NOT NULL,
  `prior_profile_json` json DEFAULT NULL,
  `status` enum('COMPLETED','FAILED','GENERATING','READY') NOT NULL,
  `target_count` int DEFAULT NULL,
  `lecture_id` bigint NOT NULL,
  `material_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`exam_session_id`),
  KEY `FKjfnqdolhh1f9rwtpy5ef4bip3` (`lecture_id`),
  KEY `FKwb32bt53e2v9ogoogjfa006g` (`material_id`),
  KEY `FKqrpl6nuh8v69kxmpoaeijqxjc` (`user_id`),
  CONSTRAINT `FKjfnqdolhh1f9rwtpy5ef4bip3` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`),
  CONSTRAINT `FKqrpl6nuh8v69kxmpoaeijqxjc` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FKwb32bt53e2v9ogoogjfa006g` FOREIGN KEY (`material_id`) REFERENCES `material` (`material_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `generated_content` (
  `generated_content_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `ai_question_id` varchar(255) DEFAULT NULL,
  `content_data` text NOT NULL,
  `content_type` enum('SCRIPT','SUMMARY','VISUAL_AID') NOT NULL,
  `material_references` tinytext,
  `lecture_id` bigint NOT NULL,
  `session_id` bigint DEFAULT NULL,
  `student_id` bigint DEFAULT NULL,
  PRIMARY KEY (`generated_content_id`),
  KEY `FKlafp82jwia2kubh2wpukevoud` (`lecture_id`),
  KEY `FK5edoomy9qg8ewi5rtmmptvho5` (`session_id`),
  KEY `FKllkr5dkmbht2nt7e5ev5tdgd4` (`student_id`),
  CONSTRAINT `FK5edoomy9qg8ewi5rtmmptvho5` FOREIGN KEY (`session_id`) REFERENCES `generation_sessions` (`session_id`),
  CONSTRAINT `FKlafp82jwia2kubh2wpukevoud` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`),
  CONSTRAINT `FKllkr5dkmbht2nt7e5ev5tdgd4` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `generation_sessions` (
  `session_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `chapter_content_list_json` json DEFAULT NULL,
  `current_phase` enum('COMPLETED','FAILED','PHASE1','PHASE2','PHASE3','PHASE4','PHASE5') NOT NULL,
  `draft_plan_json` json DEFAULT NULL,
  `error_message` text,
  `final_document` longtext,
  `finalized_brief_json` json DEFAULT NULL,
  `progress_percentage` int DEFAULT NULL,
  `user_prompt` text,
  `verified_content_json` json DEFAULT NULL,
  `lecture_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`session_id`),
  KEY `FKah9t94ta2by0xa883cw5yo4ht` (`lecture_id`),
  KEY `FKgsxkvj9gl48luxymoxt9knlfy` (`user_id`),
  CONSTRAINT `FKah9t94ta2by0xa883cw5yo4ht` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`),
  CONSTRAINT `FKgsxkvj9gl48luxymoxt9knlfy` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `lectures` (
  `lecture_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `ai_generated_status` enum('COMPLETED','FAILED','PENDING','PROCESSING') NOT NULL,
  `description` longtext,
  `title` varchar(255) NOT NULL,
  `week_number` int NOT NULL,
  `course_id` bigint NOT NULL,
  PRIMARY KEY (`lecture_id`),
  KEY `FKsj4m8ipr4qnehoyxk7kbu3ide` (`course_id`),
  CONSTRAINT `FKsj4m8ipr4qnehoyxk7kbu3ide` FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `material` (
  `material_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `display_name` varchar(255) DEFAULT NULL,
  `file_path` varchar(255) DEFAULT NULL,
  `material_type` varchar(255) DEFAULT NULL,
  `uploaded_by` bigint DEFAULT NULL,
  `url` varchar(255) DEFAULT NULL,
  `lecture_id` bigint NOT NULL,
  PRIMARY KEY (`material_id`),
  KEY `FKqvrsg5ip20u86i9ojyb599wif` (`lecture_id`),
  CONSTRAINT `FKqvrsg5ip20u86i9ojyb599wif` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `notifications` (
  `notification_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `body` varchar(1000) DEFAULT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `resource_id` bigint DEFAULT NULL,
  `resource_type` varchar(40) DEFAULT NULL,
  `title` varchar(200) NOT NULL,
  `type` enum('COURSE_JOIN_APPROVED','COURSE_JOIN_BLOCKED','COURSE_JOIN_REJECTED','COURSE_MEMBER_BLOCKED','COURSE_MEMBER_REMOVED') NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`notification_id`),
  KEY `idx_notification_user_read` (`user_id`,`read_at`),
  KEY `idx_notification_user_created` (`user_id`,`created_at`),
  CONSTRAINT `FK9y21adhxn0ayjhfocscqox7bh` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `student_answers` (
  `answer_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `descriptive_answer` tinytext,
  `evaluation_metadata` json DEFAULT NULL,
  `feedback_json` json DEFAULT NULL,
  `is_correct` bit(1) DEFAULT NULL,
  `score` int DEFAULT NULL,
  `teacher_comment` tinytext,
  `choice_option_id` bigint DEFAULT NULL,
  `exam_question_id` bigint NOT NULL,
  `submission_id` bigint NOT NULL,
  PRIMARY KEY (`answer_id`),
  KEY `FK1ggblplnbj2dlg19il538yi7d` (`choice_option_id`),
  KEY `FKmqi0dhucfpcfbgnxy64v4ih4v` (`exam_question_id`),
  KEY `FKf0s9eifp1j4hxy0v2of0obgqf` (`submission_id`),
  CONSTRAINT `FK1ggblplnbj2dlg19il538yi7d` FOREIGN KEY (`choice_option_id`) REFERENCES `choice_options` (`choice_option_id`),
  CONSTRAINT `FKf0s9eifp1j4hxy0v2of0obgqf` FOREIGN KEY (`submission_id`) REFERENCES `submissions` (`submission_id`),
  CONSTRAINT `FKmqi0dhucfpcfbgnxy64v4ih4v` FOREIGN KEY (`exam_question_id`) REFERENCES `exam_questions` (`exam_question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `student_inquiries` (
  `inquiry_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `agent_answer` tinytext,
  `inquiry_text` tinytext NOT NULL,
  `lecture_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  PRIMARY KEY (`inquiry_id`),
  KEY `FKql9m31o7bgq3pghmj6lwj7b60` (`lecture_id`),
  KEY `FKh115q5g9n5xortv5plkpwm2s0` (`student_id`),
  CONSTRAINT `FKh115q5g9n5xortv5plkpwm2s0` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`),
  CONSTRAINT `FKql9m31o7bgq3pghmj6lwj7b60` FOREIGN KEY (`lecture_id`) REFERENCES `lectures` (`lecture_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `students` (
  `student_id` bigint NOT NULL AUTO_INCREMENT,
  `class_number` varchar(255) NOT NULL,
  `grade` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`student_id`),
  UNIQUE KEY `UKg4fwvutq09fjdlb4bb0byp7t` (`user_id`),
  CONSTRAINT `FKdt1cjx5ve5bdabmuuf3ibrwaq` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `submissions` (
  `submission_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `status` enum('GRADED','SUBMITTED') NOT NULL,
  `assessment_id` bigint NOT NULL,
  `exam_result_id` bigint DEFAULT NULL,
  `student_id` bigint NOT NULL,
  PRIMARY KEY (`submission_id`),
  KEY `FKlmd53jhjpuaoph815jk7vbh57` (`assessment_id`),
  KEY `FKdgkljul6iumjy1sr2w3lkopmj` (`exam_result_id`),
  KEY `FKhwebuw14r6lb2ja85w9mwa8vf` (`student_id`),
  CONSTRAINT `FKdgkljul6iumjy1sr2w3lkopmj` FOREIGN KEY (`exam_result_id`) REFERENCES `exam_results` (`exam_result_id`),
  CONSTRAINT `FKhwebuw14r6lb2ja85w9mwa8vf` FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`),
  CONSTRAINT `FKlmd53jhjpuaoph815jk7vbh57` FOREIGN KEY (`assessment_id`) REFERENCES `assessments` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `teachers` (
  `teacher_id` bigint NOT NULL AUTO_INCREMENT,
  `department` varchar(255) NOT NULL,
  `school_name` varchar(255) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`teacher_id`),
  UNIQUE KEY `UKcd1k6xwg9jqtiwx9ybnxpmoh9` (`user_id`),
  CONSTRAINT `FKb8dct7w2j1vl1r2bpstw5isc0` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `birth_date` date DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `full_name` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `phone_num` varchar(255) DEFAULT NULL,
  `role` enum('STUDENT','TEACHER') NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
