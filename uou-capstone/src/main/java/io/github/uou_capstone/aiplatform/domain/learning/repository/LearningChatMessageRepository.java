package io.github.uou_capstone.aiplatform.domain.learning.repository;

import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningChatMessageRepository extends JpaRepository<LearningChatMessage, Long> {

    List<LearningChatMessage> findByChatSessionIdOrderByCreatedAtAscIdAsc(Long chatSessionId);
}
