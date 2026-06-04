package io.github.uou_capstone.aiplatform.domain.learning.service;

import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatMessageRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatSessionRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningIntegratedEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class LearningDataCleanupServiceTest {

    @Mock private LearningSessionEvidenceRepository sessionEvidenceRepository;
    @Mock private LearningIntegratedEvidenceRepository integratedEvidenceRepository;
    @Mock private LearningChatMessageRepository chatMessageRepository;
    @Mock private LearningChatSessionRepository chatSessionRepository;

    @InjectMocks
    private LearningDataCleanupService cleanupService;

    @Test
    void deleteByCourseId_removesLearningRowsBeforeSessions() {
        cleanupService.deleteByCourseId(30L);

        InOrder inOrder = inOrder(
                sessionEvidenceRepository,
                integratedEvidenceRepository,
                chatMessageRepository,
                chatSessionRepository
        );
        inOrder.verify(sessionEvidenceRepository).deleteByCourseId(30L);
        inOrder.verify(integratedEvidenceRepository).deleteByLectureCourseId(30L);
        inOrder.verify(chatMessageRepository).deleteByLectureCourseId(30L);
        inOrder.verify(chatSessionRepository).deleteByLectureCourseId(30L);
    }

    @Test
    void deleteByLectureId_removesLearningRowsBeforeSessions() {
        cleanupService.deleteByLectureId(40L);

        InOrder inOrder = inOrder(
                sessionEvidenceRepository,
                integratedEvidenceRepository,
                chatMessageRepository,
                chatSessionRepository
        );
        inOrder.verify(sessionEvidenceRepository).deleteByLectureId(40L);
        inOrder.verify(integratedEvidenceRepository).deleteByLectureId(40L);
        inOrder.verify(chatMessageRepository).deleteByLectureId(40L);
        inOrder.verify(chatSessionRepository).deleteByLectureId(40L);
    }
}
