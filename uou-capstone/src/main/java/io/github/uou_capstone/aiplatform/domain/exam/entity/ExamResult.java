package io.github.uou_capstone.aiplatform.domain.exam.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 시험 응시 결과
 * 시험 응시 결과 및 AI 피드백 저장
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "exam_results")
public class ExamResult extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exam_result_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exam_session_id", nullable = false)
    private io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession examSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private Submission submission;  // 버전 1 호환 (NULL 허용)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;  // 응시한 사용자 (Student)

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_feedback_json", columnDefinition = "JSON")
    private Map<String, Object> userFeedbackJson;  // User 피드백 Profile (시험 응시 후 결과 분석)

    @Column(name = "total_score", precision = 5, scale = 2)
    private BigDecimal totalScore;  // 총점

    @Column(name = "max_score", precision = 5, scale = 2)
    private BigDecimal maxScore;  // 만점

    @Lob
    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;  // AI 생성 전체 총평

    @Column(name = "completed_at")
    private LocalDateTime completedAt;  // 완료 시각

    @Builder
    public ExamResult(io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession examSession, Submission submission, User user) {
        this.examSession = examSession;
        this.submission = submission;
        this.user = user;
        this.completedAt = LocalDateTime.now();
    }

    public void updateScores(BigDecimal totalScore, BigDecimal maxScore) {
        this.totalScore = totalScore;
        this.maxScore = maxScore;
    }

    public void updateUserFeedback(Map<String, Object> userFeedbackJson) {
        this.userFeedbackJson = userFeedbackJson;
    }

    public void updateOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback;
    }

    public void markAsCompleted() {
        this.completedAt = LocalDateTime.now();
    }
}
