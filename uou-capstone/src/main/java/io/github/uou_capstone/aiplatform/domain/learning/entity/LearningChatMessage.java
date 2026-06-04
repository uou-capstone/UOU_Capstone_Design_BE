package io.github.uou_capstone.aiplatform.domain.learning.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "learning_chat_messages",
        indexes = {
                @Index(name = "idx_learning_chat_message_session_created", columnList = "chat_session_id, created_at")
        })
public class LearningChatMessage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private LearningChatSession chatSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LearningChatMessageRole role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Builder
    public LearningChatMessage(LearningChatSession chatSession,
                               LearningChatMessageRole role,
                               String content,
                               Integer pageNumber) {
        this.chatSession = chatSession;
        this.role = role;
        this.content = content;
        this.pageNumber = pageNumber;
    }
}
