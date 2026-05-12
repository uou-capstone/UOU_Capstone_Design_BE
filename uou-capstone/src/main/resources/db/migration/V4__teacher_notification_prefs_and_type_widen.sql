-- ============================================================
-- V4__teacher_notification_prefs_and_type_widen.sql
--
-- 1) notifications.type 컬럼을 ENUM(...) 에서 VARCHAR(40) 으로 정렬.
--    NotificationType.java 의 주석/설계 의도(@Enumerated(STRING) + varchar(40))와 일치시킨다.
--    이후 enum 값 추가 시 DDL 변경이 영원히 불필요해진다.
--
-- 2) 교사 알림 수신 설정 테이블 신설.
--    include_self_action_notifications=true 일 때만 본인 작업 확인 알림(TEACHER_ACTION_CONFIRMED)이
--    교사 본인에게 발행된다. 학생/시스템 이벤트는 항상 발행.
-- ============================================================

ALTER TABLE `notifications`
  MODIFY COLUMN `type` VARCHAR(40) NOT NULL;

CREATE TABLE `teacher_notification_preferences` (
  `pref_id`                            bigint NOT NULL AUTO_INCREMENT,
  `teacher_id`                         bigint NOT NULL,
  `include_self_action_notifications`  bit(1) NOT NULL DEFAULT b'0',
  `created_at`                         datetime(6) DEFAULT NULL,
  `updated_at`                         datetime(6) DEFAULT NULL,
  PRIMARY KEY (`pref_id`),
  UNIQUE KEY `uq_tnp_teacher` (`teacher_id`),
  CONSTRAINT `FK_tnp_teacher` FOREIGN KEY (`teacher_id`)
    REFERENCES `teachers` (`teacher_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
