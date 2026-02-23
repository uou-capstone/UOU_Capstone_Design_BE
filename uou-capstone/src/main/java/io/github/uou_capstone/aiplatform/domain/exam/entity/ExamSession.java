package io.github.uou_capstone.aiplatform.domain.exam.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 시험 생성 세션
 * 버전 2의 5가지 시험 유형 생성 상태를 관리
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "exam_sessions")
public class ExamSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;  // 생성 요청한 사용자

    @Enumerated(EnumType.STRING)
    @Column(name = "exam_type", nullable = false)
    private ExamType examType;  // FLASH_CARD, OX_PROBLEM, FIVE_CHOICE, SHORT_ANSWER, DEBATE

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prior_profile_json", columnDefinition = "JSON")
    private Map<String, Object> priorProfileJson;  // 사전 Profile (TestProfile)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exam_content_json", columnDefinition = "JSON")
    private Map<String, Object> examContentJson;  // 생성된 시험 내용 (문제 리스트)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_log_json", columnDefinition = "JSON")
    private Map<String, Object> evaluationLogJson;  // 채점 결과 (GradingResponse)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExamStatus status = ExamStatus.GENERATING;  // GENERATING, READY, COMPLETED, FAILED

    @Column(name = "target_count")
    private Integer targetCount = 10;  // 생성할 문제/카드 수

    // 토론형 시험 전용 필드
    @Enumerated(EnumType.STRING)
    @Column(name = "debate_phase")
    private DebatePhase debatePhase;  // 토론형 시험 Phase (DEBATE 타입일 때만 사용)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "debate_history_json", columnDefinition = "JSON")
    private Map<String, Object> debateHistoryJson;  // 토론 대화 기록 (Phase 2)

    @Builder
    public ExamSession(Lecture lecture, User user, ExamType examType, Integer targetCount) {
        this.lecture = lecture;
        this.user = user;
        this.examType = examType;
        this.targetCount = targetCount != null ? targetCount : 10;
        this.status = ExamStatus.GENERATING;
    }

    public void updateStatus(ExamStatus status) {
        this.status = status;
    }

    public void updatePriorProfile(Map<String, Object> priorProfile) {
        this.priorProfileJson = priorProfile;
    }

    public void updateExamContent(Map<String, Object> examContent) {
        this.examContentJson = examContent;
        this.status = ExamStatus.READY;
    }

    public void updateEvaluationLog(Map<String, Object> evaluationLog) {
        this.evaluationLogJson = evaluationLog;
        this.status = ExamStatus.COMPLETED;
    }

    public void markAsFailed() {
        this.status = ExamStatus.FAILED;
    }

    // 토론형 시험 전용 메서드
    public void updateDebatePhase(DebatePhase phase) {
        this.debatePhase = phase;
    }

    public void updateDebateHistory(Map<String, Object> debateHistory) {
        this.debateHistoryJson = debateHistory;
    }
}
