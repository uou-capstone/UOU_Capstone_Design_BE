package io.github.uou_capstone.aiplatform.domain.course.report.studentchat.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_report_chat_messages",
        indexes = {
                @Index(name = "idx_srcm_session_created", columnList = "report_chat_session_id, created_at")
        })
public class StudentReportChatMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_chat_message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_chat_session_id", nullable = false)
    private StudentReportChatSession chatSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StudentReportChatMessageRole role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Builder
    public StudentReportChatMessage(StudentReportChatSession chatSession,
                                    StudentReportChatMessageRole role,
                                    String content) {
        this.chatSession = chatSession;
        this.role = role;
        this.content = content;
    }
}
