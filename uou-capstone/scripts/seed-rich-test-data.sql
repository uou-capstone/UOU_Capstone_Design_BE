-- Development-only rich seed data for the current database.
--
-- What this script does:
--   1. Creates a dedicated seed teacher and two [SEED] courses.
--   2. Enrolls every existing student into those seed courses.
--   3. Adds lectures, materials, notices, discussions, attendance, exams,
--      submissions, grading results, learning chats, inquiries, and reports.
--   4. Assigns each student a rotating learning level:
--      BEGINNER, BASIC, INTERMEDIATE, ADVANCED.
--
-- Run examples:
--   mysql -uroot -p uoucapstone < scripts/seed-rich-test-data.sql
--   docker exec -i main-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" uoucapstone < scripts/seed-rich-test-data.sql
--   docker exec -i dev-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" uoucapstone_dev < scripts/seed-rich-test-data.sql
--
-- If the database has no students yet, run scripts/seed-students.sql first.

SET @seed_password_hash = '$2a$10$oWw5P4Ke9ZbMmPqkMyBwz.Xun5HOW9TXCuLIy.yO7Y0nLBBN4SHDC';

INSERT IGNORE INTO users (email, password, full_name, birth_date, phone_num, role)
VALUES ('seed.teacher@example.com', @seed_password_hash, 'Seed Teacher', '1985-03-01', '010-9000-0001', 'TEACHER');

INSERT INTO teachers (user_id, department, school_name)
SELECT u.user_id, 'Computer Education', 'UOU Seed School'
FROM users u
LEFT JOIN teachers t ON t.user_id = u.user_id
WHERE u.email = 'seed.teacher@example.com'
  AND t.teacher_id IS NULL;

SET @seed_teacher_user_id = (SELECT user_id FROM users WHERE email = 'seed.teacher@example.com');
SET @seed_teacher_id = (SELECT teacher_id FROM teachers WHERE user_id = @seed_teacher_user_id);

DROP TEMPORARY TABLE IF EXISTS seed_students;
CREATE TEMPORARY TABLE seed_students AS
SELECT ranked.student_id,
       ranked.user_id,
       ranked.email,
       ranked.full_name,
       ranked.rn,
       CASE MOD(ranked.rn - 1, 4)
           WHEN 0 THEN 'BEGINNER'
           WHEN 1 THEN 'BASIC'
           WHEN 2 THEN 'INTERMEDIATE'
           ELSE 'ADVANCED'
       END AS learning_level,
       CASE MOD(ranked.rn - 1, 4)
           WHEN 0 THEN 55
           WHEN 1 THEN 68
           WHEN 2 THEN 82
           ELSE 94
       END AS score_base
FROM (
    SELECT s.student_id,
           u.user_id,
           u.email,
           u.full_name,
           ROW_NUMBER() OVER (ORDER BY s.student_id) AS rn
    FROM students s
    JOIN users u ON u.user_id = s.user_id
    WHERE u.role = 'STUDENT'
) ranked;

SET @seed_student_count = (SELECT COUNT(*) FROM seed_students);
SET @first_seed_student_user_id = (SELECT user_id FROM seed_students ORDER BY rn LIMIT 1);

DROP TEMPORARY TABLE IF EXISTS seed_students_peer_source;
CREATE TEMPORARY TABLE seed_students_peer_source AS
SELECT rn, user_id
FROM seed_students;

DROP TEMPORARY TABLE IF EXISTS seed_peer_users;
CREATE TEMPORARY TABLE seed_peer_users AS
SELECT ss.student_id,
       COALESCE(peer.user_id, @first_seed_student_user_id, ss.user_id) AS peer_user_id
FROM seed_students ss
LEFT JOIN seed_students_peer_source peer
       ON peer.rn = CASE
           WHEN ss.rn = @seed_student_count THEN 1
           ELSE ss.rn + 1
       END;

INSERT INTO courses (created_at, updated_at, description, invitation_code, title, teacher_id)
SELECT NOW(6), NOW(6), '대량 테스트 데이터 검증용 AI 튜터 강의실입니다.', 'SEED-AI-2026', '[SEED] AI 튜터 종합 테스트', @seed_teacher_id
WHERE NOT EXISTS (SELECT 1 FROM courses WHERE invitation_code = 'SEED-AI-2026');

INSERT INTO courses (created_at, updated_at, description, invitation_code, title, teacher_id)
SELECT NOW(6), NOW(6), '시험, 토론, 출석, 리포트 기능을 함께 검증하는 강의실입니다.', 'SEED-DB-2026', '[SEED] 데이터베이스 실습 테스트', @seed_teacher_id
WHERE NOT EXISTS (SELECT 1 FROM courses WHERE invitation_code = 'SEED-DB-2026');

DROP TEMPORARY TABLE IF EXISTS seed_courses;
CREATE TEMPORARY TABLE seed_courses AS
SELECT course_id, title, teacher_id
FROM courses
WHERE invitation_code IN ('SEED-AI-2026', 'SEED-DB-2026');

INSERT IGNORE INTO enrollments (created_at, updated_at, status, course_id, student_id)
SELECT DATE_SUB(NOW(6), INTERVAL ss.rn DAY), NOW(6), 'ACTIVE', sc.course_id, ss.student_id
FROM seed_courses sc
CROSS JOIN seed_students ss;

INSERT INTO course_join_requests (created_at, updated_at, status, course_id, student_id)
SELECT DATE_SUB(NOW(6), INTERVAL ss.rn DAY), NOW(6), 'APPROVED', sc.course_id, ss.student_id
FROM seed_courses sc
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM course_join_requests cjr
    WHERE cjr.course_id = sc.course_id
      AND cjr.student_id = ss.student_id
);

INSERT INTO lectures (created_at, updated_at, ai_generated_status, description, title, week_number, course_id)
SELECT DATE_SUB(NOW(6), INTERVAL lecture_seed.week_number DAY),
       NOW(6),
       'COMPLETED',
       lecture_seed.description,
       CONCAT('[SEED] ', lecture_seed.title),
       lecture_seed.week_number,
       sc.course_id
FROM seed_courses sc
JOIN (
    SELECT 1 AS week_number, '학습 목표와 사전 진단' AS title, '학생 수준을 진단하고 개인화 학습 목표를 세웁니다.' AS description
    UNION ALL SELECT 2, '핵심 개념 정리', '핵심 개념, 용어, 예제를 정리합니다.'
    UNION ALL SELECT 3, '토론과 문제 해결', '질문, 토론, 논증 활동을 통해 개념을 적용합니다.'
    UNION ALL SELECT 4, '종합 평가와 피드백', '형성평가와 개별 피드백으로 학습 상태를 점검합니다.'
) lecture_seed
WHERE NOT EXISTS (
    SELECT 1
    FROM lectures l
    WHERE l.course_id = sc.course_id
      AND l.week_number = lecture_seed.week_number
      AND l.title = CONCAT('[SEED] ', lecture_seed.title)
);

DROP TEMPORARY TABLE IF EXISTS seed_lectures;
CREATE TEMPORARY TABLE seed_lectures AS
SELECT l.lecture_id,
       l.course_id,
       l.week_number,
       l.title,
       ROW_NUMBER() OVER (PARTITION BY l.course_id ORDER BY l.week_number, l.lecture_id) AS lecture_rank
FROM lectures l
JOIN seed_courses sc ON sc.course_id = l.course_id
WHERE l.title LIKE '[SEED]%';

INSERT INTO material (created_at, updated_at, display_name, file_path, material_type, uploaded_by, url, lecture_id)
SELECT NOW(6),
       NOW(6),
       CONCAT(REPLACE(sl.title, '[SEED] ', ''), ' 학습자료.pdf'),
       CONCAT('/seed/materials/lecture-', sl.lecture_id, '.pdf'),
       'PDF',
       @seed_teacher_user_id,
       CONCAT('https://example.com/seed/materials/lecture-', sl.lecture_id, '.pdf'),
       sl.lecture_id
FROM seed_lectures sl
WHERE NOT EXISTS (
    SELECT 1
    FROM material m
    WHERE m.lecture_id = sl.lecture_id
      AND m.display_name = CONCAT(REPLACE(sl.title, '[SEED] ', ''), ' 학습자료.pdf')
);

DROP TEMPORARY TABLE IF EXISTS seed_materials;
CREATE TEMPORARY TABLE seed_materials AS
SELECT m.material_id, m.lecture_id
FROM material m
JOIN seed_lectures sl ON sl.lecture_id = m.lecture_id
WHERE m.display_name LIKE '%학습자료.pdf';

INSERT INTO notices (course_id, author_teacher_id, title, content_markdown, category, priority, pinned, created_at, updated_at)
SELECT sc.course_id,
       @seed_teacher_id,
       notice_seed.title,
       notice_seed.content_markdown,
       notice_seed.category,
       notice_seed.priority,
       notice_seed.pinned,
       DATE_SUB(NOW(6), INTERVAL notice_seed.sort_order DAY),
       NOW(6)
FROM seed_courses sc
JOIN (
    SELECT 1 AS sort_order, '[SEED] 학습 일정 안내' AS title, '이번 주는 진단평가, 토론, 개인별 피드백 활동을 모두 진행합니다.' AS content_markdown, 'GENERAL' AS category, 'IMPORTANT' AS priority, TRUE AS pinned
    UNION ALL SELECT 2, '[SEED] 시험 응시 안내', '5지선다, OX, 단답형 예시 시험 데이터가 준비되어 있습니다.', 'EXAM', 'IMPORTANT', FALSE
    UNION ALL SELECT 3, '[SEED] 자료 확인 요청', '각 강의별 PDF 자료와 학습 채팅 기록을 확인해 주세요.', 'MATERIAL', 'NORMAL', FALSE
) notice_seed
WHERE NOT EXISTS (
    SELECT 1
    FROM notices n
    WHERE n.course_id = sc.course_id
      AND n.title = notice_seed.title
);

INSERT INTO notice_comments (notice_id, author_user_id, parent_comment_id, content_markdown, created_at, updated_at)
SELECT n.notice_id,
       ss.user_id,
       NULL,
       CONCAT('[SEED] ', ss.full_name, ' 확인했습니다. 현재 학습 수준은 ', ss.learning_level, '입니다.'),
       DATE_SUB(NOW(6), INTERVAL ss.rn HOUR),
       NOW(6)
FROM notices n
JOIN seed_courses sc ON sc.course_id = n.course_id
CROSS JOIN seed_students ss
WHERE n.title = '[SEED] 학습 일정 안내'
  AND NOT EXISTS (
      SELECT 1
      FROM notice_comments nc
      WHERE nc.notice_id = n.notice_id
        AND nc.author_user_id = ss.user_id
        AND nc.content_markdown LIKE '[SEED] %확인했습니다.%'
  );

INSERT INTO discussions (course_id, author_user_id, title, content_markdown, category, pinned, allow_comments, view_count, created_at, updated_at)
SELECT sc.course_id,
       @seed_teacher_user_id,
       '[SEED] 이번 주 핵심 토론 주제',
       '각자 이해한 핵심 개념과 아직 헷갈리는 부분을 공유하세요.',
       'QUESTION',
       TRUE,
       TRUE,
       30,
       DATE_SUB(NOW(6), INTERVAL 5 DAY),
       NOW(6)
FROM seed_courses sc
WHERE NOT EXISTS (
    SELECT 1
    FROM discussions d
    WHERE d.course_id = sc.course_id
      AND d.title = '[SEED] 이번 주 핵심 토론 주제'
);

INSERT INTO discussions (course_id, author_user_id, title, content_markdown, category, pinned, allow_comments, view_count, created_at, updated_at)
SELECT sc.course_id,
       ss.user_id,
       CONCAT('[SEED] ', ss.full_name, ' 질문 ', topic_seed.topic_no),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN CONCAT('기초 개념은 이해했지만 ', topic_seed.topic, ' 부분에서 예시가 더 필요합니다.')
           WHEN 'BASIC' THEN CONCAT(topic_seed.topic, ' 개념은 따라가고 있으나 문제 풀이 순서가 헷갈립니다.')
           WHEN 'INTERMEDIATE' THEN CONCAT(topic_seed.topic, '을 실제 사례에 적용하는 방식에 대해 의견을 나누고 싶습니다.')
           ELSE CONCAT(topic_seed.topic, '을 확장해서 다른 알고리즘과 비교하면 어떤 장단점이 있을까요?')
       END,
       CASE topic_seed.topic_no WHEN 1 THEN 'QUESTION' ELSE 'FREE' END,
       FALSE,
       TRUE,
       ss.score_base DIV 2,
       DATE_SUB(NOW(6), INTERVAL (ss.rn + topic_seed.topic_no) HOUR),
       NOW(6)
FROM seed_courses sc
CROSS JOIN seed_students ss
JOIN (
    SELECT 1 AS topic_no, '핵심 개념' AS topic
    UNION ALL SELECT 2, '응용 문제'
) topic_seed
WHERE NOT EXISTS (
    SELECT 1
    FROM discussions d
    WHERE d.course_id = sc.course_id
      AND d.author_user_id = ss.user_id
      AND d.title = CONCAT('[SEED] ', ss.full_name, ' 질문 ', topic_seed.topic_no)
);

INSERT INTO discussion_comments (discussion_id, author_user_id, parent_comment_id, content_markdown, created_at, updated_at)
SELECT d.discussion_id,
       @seed_teacher_user_id,
       NULL,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 핵심 용어부터 다시 정리해 보면 좋겠습니다. 강의 1주차 자료를 먼저 확인하세요.'
           WHEN 'BASIC' THEN '[SEED] 풀이 절차를 단계별로 써 보면 다음 문제에서 실수가 줄어듭니다.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 적용 관점이 좋습니다. 반례도 함께 찾아보세요.'
           ELSE '[SEED] 비교 관점이 좋습니다. 성능과 설명 가능성을 함께 논의해 보세요.'
       END,
       DATE_ADD(d.created_at, INTERVAL 20 MINUTE),
       NOW(6)
FROM discussions d
JOIN seed_courses sc ON sc.course_id = d.course_id
JOIN seed_students ss ON ss.user_id = d.author_user_id
WHERE d.title LIKE '[SEED] % 질문 %'
  AND NOT EXISTS (
      SELECT 1
      FROM discussion_comments dc
      WHERE dc.discussion_id = d.discussion_id
        AND dc.author_user_id = @seed_teacher_user_id
        AND dc.content_markdown LIKE '[SEED] %'
  );

INSERT INTO discussion_comments (discussion_id, author_user_id, parent_comment_id, content_markdown, created_at, updated_at)
SELECT d.discussion_id,
       spu.peer_user_id,
       NULL,
       '[SEED] 저도 비슷한 부분을 고민했습니다. 자료의 예제를 같이 보면 도움이 됩니다.',
       DATE_ADD(d.created_at, INTERVAL 40 MINUTE),
       NOW(6)
FROM discussions d
JOIN seed_students ss ON ss.user_id = d.author_user_id
JOIN seed_peer_users spu ON spu.student_id = ss.student_id
WHERE d.title LIKE '[SEED] % 질문 %'
  AND NOT EXISTS (
      SELECT 1
      FROM discussion_comments dc
      WHERE dc.discussion_id = d.discussion_id
        AND dc.author_user_id = spu.peer_user_id
        AND dc.content_markdown = '[SEED] 저도 비슷한 부분을 고민했습니다. 자료의 예제를 같이 보면 도움이 됩니다.'
  );

INSERT INTO attendance_sessions (course_id, lecture_id, title, session_date, start_time, end_time, created_by_teacher_id, created_at, updated_at)
SELECT sl.course_id,
       sl.lecture_id,
       CONCAT('[SEED] ', sl.week_number, '주차 출석'),
       DATE_SUB(CURDATE(), INTERVAL (8 - sl.week_number) DAY),
       '09:00:00',
       '09:50:00',
       @seed_teacher_id,
       NOW(6),
       NOW(6)
FROM seed_lectures sl
WHERE NOT EXISTS (
    SELECT 1
    FROM attendance_sessions ats
    WHERE ats.course_id = sl.course_id
      AND ats.title = CONCAT('[SEED] ', sl.week_number, '주차 출석')
);

INSERT INTO attendance_records (attendance_session_id, student_id, status, marked_at, marked_by_teacher_id, note, created_at, updated_at)
SELECT ats.attendance_session_id,
       ss.student_id,
       CASE
           WHEN ss.learning_level = 'ADVANCED' THEN 'PRESENT'
           WHEN ss.learning_level = 'INTERMEDIATE' AND MOD(ats.attendance_session_id + ss.rn, 5) = 0 THEN 'LATE'
           WHEN ss.learning_level = 'BASIC' AND MOD(ats.attendance_session_id + ss.rn, 4) = 0 THEN 'LATE'
           WHEN ss.learning_level = 'BEGINNER' AND MOD(ats.attendance_session_id + ss.rn, 3) = 0 THEN 'ABSENT'
           ELSE 'PRESENT'
       END,
       TIMESTAMP(ats.session_date, ats.start_time),
       @seed_teacher_id,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 기초 보강 대상'
           WHEN 'BASIC' THEN '[SEED] 과제 확인 필요'
           WHEN 'INTERMEDIATE' THEN '[SEED] 안정적 참여'
           ELSE '[SEED] 우수 참여'
       END,
       NOW(6),
       NOW(6)
FROM attendance_sessions ats
JOIN seed_courses sc ON sc.course_id = ats.course_id
CROSS JOIN seed_students ss
WHERE ats.title LIKE '[SEED] %주차 출석'
  AND NOT EXISTS (
      SELECT 1
      FROM attendance_records ar
      WHERE ar.attendance_session_id = ats.attendance_session_id
        AND ar.student_id = ss.student_id
  );

DROP TEMPORARY TABLE IF EXISTS seed_exam_lectures;
CREATE TEMPORARY TABLE seed_exam_lectures AS
SELECT sc.course_id,
       MAX(CASE WHEN sl.lecture_rank = 1 THEN sl.lecture_id END) AS lecture_one_id,
       MAX(CASE WHEN sl.lecture_rank = 2 THEN sl.lecture_id END) AS lecture_two_id,
       MAX(CASE WHEN sl.lecture_rank = 3 THEN sl.lecture_id END) AS lecture_three_id,
       MAX(CASE WHEN sl.lecture_rank = 4 THEN sl.lecture_id END) AS lecture_four_id
FROM seed_courses sc
JOIN seed_lectures sl ON sl.course_id = sc.course_id
GROUP BY sc.course_id;

INSERT INTO exam_sessions (
    created_at, updated_at, debate_history_json, debate_phase, display_name,
    evaluation_log_json, exam_content_json, exam_type, prior_profile_json,
    status, target_count, lecture_id, material_id, user_id
)
SELECT NOW(6),
       NOW(6),
       NULL,
       NULL,
       CONCAT('[SEED] ', sc.title, ' 5지선다 진단평가'),
       JSON_OBJECT('seed', TRUE, 'averageTarget', 75),
       JSON_OBJECT('seed', TRUE, 'type', 'FIVE_CHOICE', 'questionCount', 5),
       'FIVE_CHOICE',
       JSON_OBJECT('source', 'seed', 'purpose', 'diagnostic'),
       'COMPLETED',
       5,
       sel.lecture_one_id,
       sm.material_id,
       @seed_teacher_user_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
LEFT JOIN seed_materials sm ON sm.lecture_id = sel.lecture_one_id
WHERE NOT EXISTS (
    SELECT 1
    FROM exam_sessions es
    WHERE es.lecture_id = sel.lecture_one_id
      AND es.user_id = @seed_teacher_user_id
      AND es.exam_type = 'FIVE_CHOICE'
      AND es.display_name = CONCAT('[SEED] ', sc.title, ' 5지선다 진단평가')
);

INSERT INTO exam_sessions (
    created_at, updated_at, debate_history_json, debate_phase, display_name,
    evaluation_log_json, exam_content_json, exam_type, prior_profile_json,
    status, target_count, lecture_id, material_id, user_id
)
SELECT NOW(6),
       NOW(6),
       NULL,
       NULL,
       CONCAT('[SEED] ', sc.title, ' 단답형 형성평가'),
       JSON_OBJECT('seed', TRUE, 'averageTarget', 75),
       JSON_OBJECT('seed', TRUE, 'type', 'SHORT_ANSWER', 'questionCount', 3),
       'SHORT_ANSWER',
       JSON_OBJECT('source', 'seed', 'purpose', 'formative'),
       'COMPLETED',
       3,
       sel.lecture_two_id,
       sm.material_id,
       @seed_teacher_user_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
LEFT JOIN seed_materials sm ON sm.lecture_id = sel.lecture_two_id
WHERE NOT EXISTS (
    SELECT 1
    FROM exam_sessions es
    WHERE es.lecture_id = sel.lecture_two_id
      AND es.user_id = @seed_teacher_user_id
      AND es.exam_type = 'SHORT_ANSWER'
      AND es.display_name = CONCAT('[SEED] ', sc.title, ' 단답형 형성평가')
);

INSERT INTO exam_sessions (
    created_at, updated_at, debate_history_json, debate_phase, display_name,
    evaluation_log_json, exam_content_json, exam_type, prior_profile_json,
    status, target_count, lecture_id, material_id, user_id
)
SELECT NOW(6),
       NOW(6),
       NULL,
       NULL,
       CONCAT('[SEED] ', sc.title, ' OX 빠른 점검'),
       JSON_OBJECT('seed', TRUE, 'averageTarget', 80),
       JSON_OBJECT('seed', TRUE, 'type', 'OX_PROBLEM', 'questionCount', 4),
       'OX_PROBLEM',
       JSON_OBJECT('source', 'seed', 'purpose', 'quick-check'),
       'COMPLETED',
       4,
       sel.lecture_four_id,
       sm.material_id,
       @seed_teacher_user_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
LEFT JOIN seed_materials sm ON sm.lecture_id = sel.lecture_four_id
WHERE NOT EXISTS (
    SELECT 1
    FROM exam_sessions es
    WHERE es.lecture_id = sel.lecture_four_id
      AND es.user_id = @seed_teacher_user_id
      AND es.exam_type = 'OX_PROBLEM'
      AND es.display_name = CONCAT('[SEED] ', sc.title, ' OX 빠른 점검')
);

INSERT INTO exam_sessions (
    created_at, updated_at, debate_history_json, debate_phase, display_name,
    evaluation_log_json, exam_content_json, exam_type, prior_profile_json,
    status, target_count, lecture_id, material_id, user_id
)
SELECT NOW(6),
       NOW(6),
       JSON_OBJECT(
           'seed', TRUE,
           'level', ss.learning_level,
           'turns', JSON_ARRAY(
               JSON_OBJECT('role', 'student', 'message', CASE ss.learning_level
                   WHEN 'BEGINNER' THEN '기본 개념을 먼저 확인하고 싶습니다.'
                   WHEN 'BASIC' THEN '예제와 풀이 절차를 연결해서 설명하겠습니다.'
                   WHEN 'INTERMEDIATE' THEN '근거를 들어 제 주장을 설명하겠습니다.'
                   ELSE '반례와 대안을 함께 제시하며 토론하겠습니다.'
               END),
               JSON_OBJECT('role', 'assistant', 'message', '근거와 예시를 포함해서 다음 주장을 정리해 보세요.')
           )
       ),
       'PHASE3',
       CONCAT('[SEED] ', ss.full_name, ' 토론형 평가'),
       JSON_OBJECT('seed', TRUE, 'score', ss.score_base),
       JSON_OBJECT('seed', TRUE, 'type', 'DEBATE', 'level', ss.learning_level),
       'DEBATE',
       JSON_OBJECT('source', 'seed', 'level', ss.learning_level),
       'COMPLETED',
       1,
       sel.lecture_three_id,
       sm.material_id,
       ss.user_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
LEFT JOIN seed_materials sm ON sm.lecture_id = sel.lecture_three_id
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM exam_sessions es
    WHERE es.lecture_id = sel.lecture_three_id
      AND es.user_id = ss.user_id
      AND es.exam_type = 'DEBATE'
      AND es.display_name = CONCAT('[SEED] ', ss.full_name, ' 토론형 평가')
);

DROP TEMPORARY TABLE IF EXISTS seed_exam_sessions;
CREATE TEMPORARY TABLE seed_exam_sessions AS
SELECT es.exam_session_id,
       es.lecture_id,
       l.course_id,
       es.exam_type,
       es.display_name,
       es.user_id
FROM exam_sessions es
JOIN lectures l ON l.lecture_id = es.lecture_id
JOIN seed_courses sc ON sc.course_id = l.course_id
WHERE es.display_name LIKE '[SEED]%';

INSERT INTO assessments (created_at, updated_at, ai_generated_status, due_date, title, assessment_type, course_id, exam_session_id)
SELECT NOW(6),
       NOW(6),
       'COMPLETED',
       DATE_ADD(NOW(6), INTERVAL 14 DAY),
       ses.display_name,
       'QUIZ',
       ses.course_id,
       ses.exam_session_id
FROM seed_exam_sessions ses
WHERE ses.exam_type IN ('FIVE_CHOICE', 'SHORT_ANSWER', 'OX_PROBLEM')
  AND NOT EXISTS (
      SELECT 1
      FROM assessments a
      WHERE a.course_id = ses.course_id
        AND a.title = ses.display_name
  );

DROP TEMPORARY TABLE IF EXISTS seed_assessments;
CREATE TEMPORARY TABLE seed_assessments AS
SELECT a.assessment_id,
       a.course_id,
       a.exam_session_id,
       es.exam_type,
       a.title
FROM assessments a
JOIN exam_sessions es ON es.exam_session_id = a.exam_session_id
JOIN seed_courses sc ON sc.course_id = a.course_id
WHERE a.title LIKE '[SEED]%';

INSERT INTO exam_questions (created_at, updated_at, created_by, exam_type, question_content, question_metadata, question_order, assessment_id, exam_session_id)
SELECT NOW(6),
       NOW(6),
       'AI',
       'FIVE_CHOICE',
       question_seed.content,
       JSON_OBJECT('seed', TRUE, 'difficulty', question_seed.difficulty, 'correctOptionNo', question_seed.correct_option_no),
       question_seed.question_order,
       sa.assessment_id,
       sa.exam_session_id
FROM seed_assessments sa
JOIN (
    SELECT 1 AS question_order, '개인화 학습에서 사전 진단이 중요한 이유는 무엇인가요?' AS content, 'EASY' AS difficulty, 2 AS correct_option_no
    UNION ALL SELECT 2, '학습 피드백을 해석할 때 가장 먼저 확인해야 하는 정보는 무엇인가요?', 'EASY', 3
    UNION ALL SELECT 3, '토론형 평가에서 좋은 주장의 조건으로 가장 적절한 것은 무엇인가요?', 'MEDIUM', 4
    UNION ALL SELECT 4, '형성평가 결과가 낮은 학생에게 우선 제공할 지원은 무엇인가요?', 'MEDIUM', 1
    UNION ALL SELECT 5, '학습 리포트의 위험 신호를 해석하는 방법으로 적절한 것은 무엇인가요?', 'HARD', 5
) question_seed
WHERE sa.exam_type = 'FIVE_CHOICE'
  AND NOT EXISTS (
      SELECT 1
      FROM exam_questions eq
      WHERE eq.exam_session_id = sa.exam_session_id
        AND eq.question_order = question_seed.question_order
  );

INSERT INTO exam_questions (created_at, updated_at, created_by, exam_type, question_content, question_metadata, question_order, assessment_id, exam_session_id)
SELECT NOW(6),
       NOW(6),
       'AI',
       'SHORT_ANSWER',
       question_seed.content,
       JSON_OBJECT('seed', TRUE, 'rubric', question_seed.rubric),
       question_seed.question_order,
       sa.assessment_id,
       sa.exam_session_id
FROM seed_assessments sa
JOIN (
    SELECT 1 AS question_order, '개인화 피드백을 설계할 때 고려해야 할 요소를 두 가지 쓰세요.' AS content, '개념 정확성, 학생 수준 반영' AS rubric
    UNION ALL SELECT 2, '토론 게시글에서 근거가 중요한 이유를 설명하세요.', '주장과 근거의 연결'
    UNION ALL SELECT 3, '학습 리포트에서 위험 학생을 발견했을 때의 후속 조치를 제안하세요.', '구체적 지원 전략'
) question_seed
WHERE sa.exam_type = 'SHORT_ANSWER'
  AND NOT EXISTS (
      SELECT 1
      FROM exam_questions eq
      WHERE eq.exam_session_id = sa.exam_session_id
        AND eq.question_order = question_seed.question_order
  );

INSERT INTO exam_questions (created_at, updated_at, created_by, exam_type, question_content, question_metadata, question_order, assessment_id, exam_session_id)
SELECT NOW(6),
       NOW(6),
       'AI',
       'OX_PROBLEM',
       question_seed.content,
       JSON_OBJECT('seed', TRUE, 'answer', question_seed.answer),
       question_seed.question_order,
       sa.assessment_id,
       sa.exam_session_id
FROM seed_assessments sa
JOIN (
    SELECT 1 AS question_order, '사전 진단은 모든 학생에게 동일한 피드백만 제공하기 위한 절차이다.' AS content, FALSE AS answer
    UNION ALL SELECT 2, '출석과 토론 참여도는 학습 리포트의 보조 근거가 될 수 있다.', TRUE
    UNION ALL SELECT 3, '단답형 평가는 학생의 설명 능력을 확인하는 데 활용할 수 있다.', TRUE
    UNION ALL SELECT 4, '토론형 평가는 근거보다 결론만 평가하는 방식이다.', FALSE
) question_seed
WHERE sa.exam_type = 'OX_PROBLEM'
  AND NOT EXISTS (
      SELECT 1
      FROM exam_questions eq
      WHERE eq.exam_session_id = sa.exam_session_id
        AND eq.question_order = question_seed.question_order
  );

INSERT INTO choice_options (created_at, updated_at, is_correct, choice_option_text, exam_question_id)
SELECT NOW(6),
       NOW(6),
       option_seed.option_no = CAST(JSON_UNQUOTE(JSON_EXTRACT(eq.question_metadata, '$.correctOptionNo')) AS UNSIGNED),
       CONCAT(option_seed.option_no, '. ', option_seed.option_text),
       eq.exam_question_id
FROM exam_questions eq
JOIN seed_assessments sa ON sa.exam_session_id = eq.exam_session_id
JOIN (
    SELECT 1 AS option_no, '학생 수준과 무관하게 동일한 과제를 제공한다.' AS option_text
    UNION ALL SELECT 2, '현재 이해도에 맞춰 학습 경로와 피드백을 조정한다.'
    UNION ALL SELECT 3, '점수만 저장하고 피드백은 제공하지 않는다.'
    UNION ALL SELECT 4, '출석 여부만 기준으로 학습 수준을 결정한다.'
    UNION ALL SELECT 5, '토론 참여를 평가에서 제외한다.'
) option_seed
WHERE eq.exam_type = 'FIVE_CHOICE'
  AND NOT EXISTS (
      SELECT 1
      FROM choice_options co
      WHERE co.exam_question_id = eq.exam_question_id
        AND co.choice_option_text = CONCAT(option_seed.option_no, '. ', option_seed.option_text)
  );

INSERT INTO submissions (created_at, updated_at, status, assessment_id, exam_result_id, student_id)
SELECT DATE_SUB(NOW(6), INTERVAL ss.rn HOUR),
       NOW(6),
       'GRADED',
       sa.assessment_id,
       NULL,
       ss.student_id
FROM seed_assessments sa
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM submissions sub
    WHERE sub.assessment_id = sa.assessment_id
      AND sub.student_id = ss.student_id
);

DROP TEMPORARY TABLE IF EXISTS seed_submissions;
CREATE TEMPORARY TABLE seed_submissions AS
SELECT sub.submission_id,
       sub.assessment_id,
       sub.student_id,
       ss.user_id,
       ss.full_name,
       ss.learning_level,
       ss.score_base,
       sa.exam_session_id,
       sa.exam_type
FROM submissions sub
JOIN seed_assessments sa ON sa.assessment_id = sub.assessment_id
JOIN seed_students ss ON ss.student_id = sub.student_id;

INSERT INTO student_answers (
    created_at, updated_at, descriptive_answer, evaluation_metadata, feedback_json,
    is_correct, score, teacher_comment, choice_option_id, exam_question_id, submission_id
)
SELECT NOW(6),
       NOW(6),
       NULL,
       JSON_OBJECT('seed', TRUE, 'level', ss.learning_level),
       JSON_OBJECT('summary', CASE
           WHEN eq.question_order <= level_rule.min_correct_order THEN '정답입니다.'
           ELSE '핵심 개념 복습이 필요합니다.'
       END),
       eq.question_order <= level_rule.min_correct_order,
       CASE WHEN eq.question_order <= level_rule.min_correct_order THEN 20 ELSE 0 END,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 기본 개념을 다시 확인하세요.'
           WHEN 'BASIC' THEN '[SEED] 오답 원인을 짧게 정리하세요.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 풀이 근거를 더 명확히 쓰세요.'
           ELSE '[SEED] 심화 문제로 확장해 보세요.'
       END,
       CASE WHEN eq.question_order <= level_rule.min_correct_order THEN correct_choice.choice_option_id ELSE wrong_choice.choice_option_id END,
       eq.exam_question_id,
       ss.submission_id
FROM seed_submissions ss
JOIN exam_questions eq ON eq.exam_session_id = ss.exam_session_id
JOIN choice_options correct_choice
     ON correct_choice.exam_question_id = eq.exam_question_id
    AND correct_choice.is_correct = TRUE
JOIN choice_options wrong_choice
     ON wrong_choice.choice_option_id = (
         SELECT MIN(co.choice_option_id)
         FROM choice_options co
         WHERE co.exam_question_id = eq.exam_question_id
           AND co.is_correct = FALSE
     )
JOIN (
    SELECT 'BEGINNER' AS learning_level, 1 AS min_correct_order
    UNION ALL SELECT 'BASIC', 3
    UNION ALL SELECT 'INTERMEDIATE', 4
    UNION ALL SELECT 'ADVANCED', 5
) level_rule ON level_rule.learning_level = ss.learning_level
WHERE ss.exam_type = 'FIVE_CHOICE'
  AND NOT EXISTS (
      SELECT 1
      FROM student_answers sa
      WHERE sa.submission_id = ss.submission_id
        AND sa.exam_question_id = eq.exam_question_id
  );

INSERT INTO student_answers (
    created_at, updated_at, descriptive_answer, evaluation_metadata, feedback_json,
    is_correct, score, teacher_comment, choice_option_id, exam_question_id, submission_id
)
SELECT NOW(6),
       NOW(6),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '기초 개념 재확인'
           WHEN 'BASIC' THEN '수준별 피드백 필요'
           WHEN 'INTERMEDIATE' THEN '근거 기반 답변'
           ELSE '심화 전략 제안'
       END,
       JSON_OBJECT('seed', TRUE, 'level', ss.learning_level),
       JSON_OBJECT('rubricMatched', ss.learning_level IN ('INTERMEDIATE', 'ADVANCED')),
       ss.learning_level IN ('INTERMEDIATE', 'ADVANCED'),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN 10
           WHEN 'BASIC' THEN 16
           WHEN 'INTERMEDIATE' THEN 25
           ELSE 33
       END,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 핵심 용어 보강'
           WHEN 'BASIC' THEN '[SEED] 근거 추가 필요'
           WHEN 'INTERMEDIATE' THEN '[SEED] 예시 추가 권장'
           ELSE '[SEED] 심화 적용 권장'
       END,
       NULL,
       eq.exam_question_id,
       ss.submission_id
FROM seed_submissions ss
JOIN exam_questions eq ON eq.exam_session_id = ss.exam_session_id
WHERE ss.exam_type = 'SHORT_ANSWER'
  AND NOT EXISTS (
      SELECT 1
      FROM student_answers sa
      WHERE sa.submission_id = ss.submission_id
        AND sa.exam_question_id = eq.exam_question_id
  );

INSERT INTO student_answers (
    created_at, updated_at, descriptive_answer, evaluation_metadata, feedback_json,
    is_correct, score, teacher_comment, choice_option_id, exam_question_id, submission_id
)
SELECT NOW(6),
       NOW(6),
       CASE
           WHEN CASE ss.learning_level
               WHEN 'BEGINNER' THEN eq.question_order = 1
               WHEN 'BASIC' THEN eq.question_order IN (1, 2)
               WHEN 'INTERMEDIATE' THEN eq.question_order IN (1, 2, 3)
               ELSE TRUE
           END THEN 'O'
           ELSE 'X'
       END,
       JSON_OBJECT('seed', TRUE, 'level', ss.learning_level),
       JSON_OBJECT('summary', CASE
           WHEN CASE ss.learning_level
               WHEN 'BEGINNER' THEN eq.question_order = 1
               WHEN 'BASIC' THEN eq.question_order IN (1, 2)
               WHEN 'INTERMEDIATE' THEN eq.question_order IN (1, 2, 3)
               ELSE TRUE
           END THEN '정답입니다.'
           ELSE '개념 확인이 필요합니다.'
       END),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN eq.question_order = 1
           WHEN 'BASIC' THEN eq.question_order IN (1, 2)
           WHEN 'INTERMEDIATE' THEN eq.question_order IN (1, 2, 3)
           ELSE TRUE
       END,
       CASE
           WHEN CASE ss.learning_level
               WHEN 'BEGINNER' THEN eq.question_order = 1
               WHEN 'BASIC' THEN eq.question_order IN (1, 2)
               WHEN 'INTERMEDIATE' THEN eq.question_order IN (1, 2, 3)
               ELSE TRUE
           END THEN 25
           ELSE 0
       END,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] OX 근거를 한 문장으로 정리하세요.'
           WHEN 'BASIC' THEN '[SEED] 헷갈린 문장을 교재에서 다시 찾으세요.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 안정적인 이해도입니다.'
           ELSE '[SEED] 빠르고 정확하게 해결했습니다.'
       END,
       NULL,
       eq.exam_question_id,
       ss.submission_id
FROM seed_submissions ss
JOIN exam_questions eq ON eq.exam_session_id = ss.exam_session_id
WHERE ss.exam_type = 'OX_PROBLEM'
  AND NOT EXISTS (
      SELECT 1
      FROM student_answers sa
      WHERE sa.submission_id = ss.submission_id
        AND sa.exam_question_id = eq.exam_question_id
  );

INSERT INTO exam_results (
    created_at, updated_at, completed_at, max_score, overall_feedback, total_score,
    user_feedback_json, exam_session_id, submission_id, user_id
)
SELECT NOW(6),
       NOW(6),
       NOW(6),
       100.00,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 기초 개념과 용어 복습이 우선입니다. 짧은 문제를 반복해 자신감을 쌓으세요.'
           WHEN 'BASIC' THEN '[SEED] 기본 흐름은 이해했습니다. 오답 유형별 보충 문제가 필요합니다.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 전반적으로 안정적입니다. 근거 설명을 더 정교하게 만들면 좋습니다.'
           ELSE '[SEED] 우수한 이해도입니다. 심화 토론과 확장 문제를 추천합니다.'
       END,
       ss.score_base,
       JSON_OBJECT('seed', TRUE, 'level', ss.learning_level, 'recommendedAction', CASE ss.learning_level
           WHEN 'BEGINNER' THEN '기초 보강'
           WHEN 'BASIC' THEN '오답 정리'
           WHEN 'INTERMEDIATE' THEN '응용 연습'
           ELSE '심화 탐구'
       END),
       ss.exam_session_id,
       ss.submission_id,
       ss.user_id
FROM seed_submissions ss
WHERE NOT EXISTS (
    SELECT 1
    FROM exam_results er
    WHERE er.exam_session_id = ss.exam_session_id
      AND er.submission_id = ss.submission_id
      AND er.user_id = ss.user_id
);

UPDATE submissions sub
JOIN exam_results er ON er.submission_id = sub.submission_id
JOIN seed_submissions ss ON ss.submission_id = sub.submission_id
SET sub.exam_result_id = er.exam_result_id,
    sub.updated_at = NOW(6)
WHERE sub.exam_result_id IS NULL;

INSERT INTO exam_results (
    created_at, updated_at, completed_at, max_score, overall_feedback, total_score,
    user_feedback_json, exam_session_id, submission_id, user_id
)
SELECT NOW(6),
       NOW(6),
       NOW(6),
       100.00,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 주장 구조를 먼저 잡고 근거를 한 가지씩 붙이는 연습이 필요합니다.'
           WHEN 'BASIC' THEN '[SEED] 핵심 주장은 있으나 근거의 구체성이 부족합니다.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 주장과 근거가 잘 연결되어 있습니다.'
           ELSE '[SEED] 반론과 재반론까지 균형 있게 제시했습니다.'
       END,
       ss.score_base,
       JSON_OBJECT('seed', TRUE, 'level', ss.learning_level, 'debatePhase', 'PHASE3'),
       ses.exam_session_id,
       NULL,
       ss.user_id
FROM seed_exam_sessions ses
JOIN seed_students ss ON ss.user_id = ses.user_id
WHERE ses.exam_type = 'DEBATE'
  AND NOT EXISTS (
      SELECT 1
      FROM exam_results er
      WHERE er.exam_session_id = ses.exam_session_id
        AND er.user_id = ss.user_id
        AND er.submission_id IS NULL
  );

INSERT INTO learning_chat_sessions (created_at, updated_at, ended_at, last_message_at, title, lecture_id, user_id)
SELECT DATE_SUB(NOW(6), INTERVAL ss.rn HOUR),
       NOW(6),
       NULL,
       NOW(6),
       CONCAT('[SEED] ', ss.full_name, ' 개인 학습 채팅'),
       sel.lecture_one_id,
       ss.user_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM learning_chat_sessions lcs
    WHERE lcs.lecture_id = sel.lecture_one_id
      AND lcs.user_id = ss.user_id
      AND lcs.title = CONCAT('[SEED] ', ss.full_name, ' 개인 학습 채팅')
);

INSERT INTO learning_chat_messages (created_at, updated_at, content, page_number, role, chat_session_id)
SELECT DATE_SUB(NOW(6), INTERVAL 30 MINUTE),
       NOW(6),
       CASE msg_seed.msg_order
           WHEN 1 THEN CONCAT('[SEED] ', ss.learning_level, ' 수준인데 오늘 학습에서 무엇부터 보면 좋을까요?')
           WHEN 2 THEN CASE ss.learning_level
               WHEN 'BEGINNER' THEN '[SEED] 1주차 핵심 용어와 쉬운 예제부터 확인하세요.'
               WHEN 'BASIC' THEN '[SEED] 개념 요약을 보고 바로 기본 문제를 풀어 보세요.'
               WHEN 'INTERMEDIATE' THEN '[SEED] 예제 변형 문제와 토론 질문을 함께 확인하세요.'
               ELSE '[SEED] 심화 질문을 만들고 다른 접근법과 비교해 보세요.'
           END
           WHEN 3 THEN '[SEED] 시험에서 자주 틀리는 부분을 알려 주세요.'
           ELSE '[SEED] 오답 기록과 토론 참여도를 함께 보고 다음 학습 목표를 조정하겠습니다.'
       END,
       CASE WHEN msg_seed.msg_order IN (1, 2) THEN 1 ELSE 2 END,
       CASE WHEN msg_seed.msg_order IN (1, 3) THEN 'USER' ELSE 'ASSISTANT' END,
       lcs.chat_session_id
FROM learning_chat_sessions lcs
JOIN seed_students ss ON ss.user_id = lcs.user_id
JOIN (
    SELECT 1 AS msg_order
    UNION ALL SELECT 2
    UNION ALL SELECT 3
    UNION ALL SELECT 4
) msg_seed
WHERE lcs.title LIKE '[SEED] % 개인 학습 채팅'
  AND NOT EXISTS (
      SELECT 1
      FROM learning_chat_messages lcm
      WHERE lcm.chat_session_id = lcs.chat_session_id
        AND lcm.content LIKE '[SEED]%'
  );

INSERT INTO student_inquiries (created_at, updated_at, agent_answer, inquiry_text, lecture_id, student_id)
SELECT NOW(6),
       NOW(6),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 용어 정의와 쉬운 예시를 먼저 확인해 보세요.'
           WHEN 'BASIC' THEN '[SEED] 풀이 절차를 세 단계로 나누어 정리해 보세요.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 현재 접근은 적절합니다. 추가 사례로 검증해 보세요.'
           ELSE '[SEED] 심화 관점에서 대안 접근법을 비교해 보세요.'
       END,
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 이 개념을 아주 쉬운 예시로 설명해 주세요.'
           WHEN 'BASIC' THEN '[SEED] 문제 풀이 순서를 다시 확인하고 싶습니다.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 실제 사례에 적용하면 어떤 점을 주의해야 하나요?'
           ELSE '[SEED] 이 방법의 한계와 대안은 무엇인가요?'
       END,
       sel.lecture_two_id,
       ss.student_id
FROM seed_courses sc
JOIN seed_exam_lectures sel ON sel.course_id = sc.course_id
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM student_inquiries si
    WHERE si.lecture_id = sel.lecture_two_id
      AND si.student_id = ss.student_id
      AND si.inquiry_text LIKE '[SEED]%'
);

INSERT INTO generated_content (created_at, updated_at, ai_question_id, content_data, content_type, material_references, lecture_id, session_id, student_id)
SELECT NOW(6),
       NOW(6),
       CONCAT('seed-q-', ss.student_id, '-', sl.lecture_id),
       CASE ss.learning_level
           WHEN 'BEGINNER' THEN '[SEED] 쉬운 용어와 짧은 예시 중심의 요약입니다.'
           WHEN 'BASIC' THEN '[SEED] 핵심 개념과 기본 문제 풀이 흐름을 정리한 자료입니다.'
           WHEN 'INTERMEDIATE' THEN '[SEED] 응용 사례와 비교 질문을 포함한 요약입니다.'
           ELSE '[SEED] 심화 관점과 확장 토론 질문을 포함한 자료입니다.'
       END,
       'SUMMARY',
       '[SEED] lecture material',
       sl.lecture_id,
       NULL,
       ss.student_id
FROM seed_lectures sl
CROSS JOIN seed_students ss
WHERE sl.lecture_rank = 1
  AND NOT EXISTS (
      SELECT 1
      FROM generated_content gc
      WHERE gc.lecture_id = sl.lecture_id
        AND gc.student_id = ss.student_id
        AND gc.ai_question_id = CONCAT('seed-q-', ss.student_id, '-', sl.lecture_id)
  );

INSERT INTO course_report_criteria (course_id, label, description, weight, created_at, updated_at)
SELECT sc.course_id,
       criteria_seed.label,
       criteria_seed.description,
       criteria_seed.weight,
       NOW(6),
       NOW(6)
FROM seed_courses sc
JOIN (
    SELECT '시험 성취도' AS label, '형성평가와 진단평가 점수를 반영합니다.' AS description, 40 AS weight
    UNION ALL SELECT '토론 참여도', '게시글, 댓글, 근거 제시 수준을 반영합니다.', 30
    UNION ALL SELECT '학습 지속성', '출석, 학습 채팅, 질문 기록을 반영합니다.', 30
) criteria_seed
WHERE NOT EXISTS (
    SELECT 1
    FROM course_report_criteria crc
    WHERE crc.course_id = sc.course_id
      AND crc.label = criteria_seed.label
);

INSERT INTO classroom_reports (
    course_id, summary_markdown, highlights_json, risks_json, coaching_priorities_json,
    source, fallback_used, fallback_reason, confidence, generated_at, created_at, updated_at
)
SELECT sc.course_id,
       '[SEED] 전반적으로 중급 이상 학생은 토론과 시험에서 안정적인 성취를 보이며, 초급 학생은 핵심 개념 복습과 짧은 문제 반복이 필요합니다.',
       JSON_ARRAY('ADVANCED 학생의 토론 근거 제시 우수', 'INTERMEDIATE 학생의 형성평가 안정적'),
       JSON_ARRAY('BEGINNER 학생의 결석 및 오답 누적', 'BASIC 학생의 단답형 근거 부족'),
       JSON_ARRAY('초급 학생 기초 보강', '기본 학생 오답 노트', '상위 학생 심화 토론'),
       'SEED',
       FALSE,
       NULL,
       'HIGH',
       NOW(6),
       NOW(6),
       NOW(6)
FROM seed_courses sc
WHERE NOT EXISTS (
    SELECT 1
    FROM classroom_reports cr
    WHERE cr.course_id = sc.course_id
);

INSERT INTO student_report_chat_sessions (course_id, student_id, last_message_at, created_at, updated_at)
SELECT sc.course_id,
       ss.student_id,
       NOW(6),
       NOW(6),
       NOW(6)
FROM seed_courses sc
CROSS JOIN seed_students ss
WHERE NOT EXISTS (
    SELECT 1
    FROM student_report_chat_sessions srcs
    WHERE srcs.course_id = sc.course_id
      AND srcs.student_id = ss.student_id
);

INSERT INTO student_report_chat_messages (report_chat_session_id, role, content, created_at, updated_at)
SELECT srcs.report_chat_session_id,
       CASE msg_seed.msg_order WHEN 1 THEN 'USER' ELSE 'ASSISTANT' END,
       CASE msg_seed.msg_order
           WHEN 1 THEN '[SEED] 이 학생의 현재 학습 상태를 요약해 주세요.'
           ELSE CASE ss.learning_level
               WHEN 'BEGINNER' THEN '[SEED] 기초 보강과 출석 관리가 우선입니다.'
               WHEN 'BASIC' THEN '[SEED] 기본 이해는 있으나 오답 정리와 짧은 복습이 필요합니다.'
               WHEN 'INTERMEDIATE' THEN '[SEED] 안정적인 성취를 보이며 응용 문제를 권장합니다.'
               ELSE '[SEED] 우수한 성취를 보이며 심화 토론과 확장 과제를 권장합니다.'
           END
       END,
       NOW(6),
       NOW(6)
FROM student_report_chat_sessions srcs
JOIN seed_courses sc ON sc.course_id = srcs.course_id
JOIN seed_students ss ON ss.student_id = srcs.student_id
JOIN (
    SELECT 1 AS msg_order
    UNION ALL SELECT 2
) msg_seed
WHERE NOT EXISTS (
    SELECT 1
    FROM student_report_chat_messages srcm
    WHERE srcm.report_chat_session_id = srcs.report_chat_session_id
      AND srcm.content LIKE '[SEED]%'
);

SET @seeded_student_count = (SELECT COUNT(*) FROM seed_students);
SET @seed_course_count = (SELECT COUNT(*) FROM seed_courses);
SET @seed_enrollment_count = (
    SELECT COUNT(*)
    FROM enrollments e
    JOIN seed_courses sc ON sc.course_id = e.course_id
);
SET @seed_discussion_count = (
    SELECT COUNT(*)
    FROM discussions d
    JOIN seed_courses sc ON sc.course_id = d.course_id
    WHERE d.title LIKE '[SEED]%'
);
SET @seed_assessment_count = (SELECT COUNT(*) FROM seed_assessments);
SET @seed_submission_count = (SELECT COUNT(*) FROM seed_submissions);
SET @seed_exam_result_count = (
    SELECT COUNT(*)
    FROM exam_results er
    JOIN seed_exam_sessions ses ON ses.exam_session_id = er.exam_session_id
);

SELECT
    @seeded_student_count AS seeded_student_count,
    @seed_course_count AS seed_course_count,
    @seed_enrollment_count AS seed_enrollment_count,
    @seed_discussion_count AS seed_discussion_count,
    @seed_assessment_count AS seed_assessment_count,
    @seed_submission_count AS seed_submission_count,
    @seed_exam_result_count AS seed_exam_result_count;
