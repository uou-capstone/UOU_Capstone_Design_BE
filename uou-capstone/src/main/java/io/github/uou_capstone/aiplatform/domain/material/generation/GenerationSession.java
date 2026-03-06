package io.github.uou_capstone.aiplatform.domain.material.generation;

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
 * 강의 자료 생성 세션
 * 버전 2의 5단계 파이프라인 상태를 관리
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "generation_sessions")
public class GenerationSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;  // 생성 요청한 사용자 (Teacher)

    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", nullable = false)
    private GenerationPhase currentPhase;

    @Lob
    @Column(name = "user_prompt", columnDefinition = "TEXT")
    private String userPrompt;  // 초기 키워드 입력

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_plan_json", columnDefinition = "JSON")
    private Map<String, Object> draftPlanJson;  // Phase1 산출물

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "finalized_brief_json", columnDefinition = "JSON")
    private Map<String, Object> finalizedBriefJson;  // Phase2 산출물

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chapter_content_list_json", columnDefinition = "JSON")
    private Map<String, Object> chapterContentListJson;  // Phase3 산출물

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verified_content_json", columnDefinition = "JSON")
    private Map<String, Object> verifiedContentJson;  // Phase4 산출물

    @Lob
    @Column(name = "final_document", columnDefinition = "LONGTEXT")
    private String finalDocument;  // Phase5 산출물 (Markdown) - 8개 챕터의 상세한 내용을 저장하기 위해 LONGTEXT 사용

    @Column(name = "progress_percentage")
    private Integer progressPercentage = 0;  // 진행률 (0-100)

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;  // 실패 시 에러 메시지

    @Builder
    public GenerationSession(Lecture lecture, User user, String userPrompt) {
        this.lecture = lecture;
        this.user = user;
        this.userPrompt = userPrompt;
        this.currentPhase = GenerationPhase.PHASE1;
        this.progressPercentage = 0;
    }

    public void updatePhase(GenerationPhase phase) {
        this.currentPhase = phase;
    }

    public void updateProgress(Integer progress) {
        if (progress != null && progress >= 0 && progress <= 100) {
            this.progressPercentage = progress;
        }
    }

    public void updateDraftPlan(Map<String, Object> draftPlan) {
        this.draftPlanJson = draftPlan;
    }

    public void updateFinalizedBrief(Map<String, Object> finalizedBrief) {
        this.finalizedBriefJson = finalizedBrief;
    }

    public void updateChapterContentList(Map<String, Object> chapterContentList) {
        this.chapterContentListJson = chapterContentList;
    }

    public void updateVerifiedContent(Map<String, Object> verifiedContent) {
        this.verifiedContentJson = verifiedContent;
    }

    public void updateFinalDocument(String finalDocument) {
        this.finalDocument = finalDocument;
        this.currentPhase = GenerationPhase.COMPLETED;
        this.progressPercentage = 100;
    }

    public void markAsFailed(String errorMessage) {
        this.currentPhase = GenerationPhase.FAILED;
        this.errorMessage = errorMessage;
    }

    public void updateErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
