package io.github.uou_capstone.aiplatform.domain.learning.service;

import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatMessageRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatSessionRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningIntegratedEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningDataCleanupService {

    private final LearningSessionEvidenceRepository sessionEvidenceRepository;
    private final LearningIntegratedEvidenceRepository integratedEvidenceRepository;
    private final LearningChatMessageRepository chatMessageRepository;
    private final LearningChatSessionRepository chatSessionRepository;

    public void deleteByCourseId(Long courseId) {
        sessionEvidenceRepository.deleteByCourseId(courseId);
        integratedEvidenceRepository.deleteByLectureCourseId(courseId);
        chatMessageRepository.deleteByLectureCourseId(courseId);
        chatSessionRepository.deleteByLectureCourseId(courseId);
    }

    public void deleteByLectureId(Long lectureId) {
        sessionEvidenceRepository.deleteByLectureId(lectureId);
        integratedEvidenceRepository.deleteByLectureId(lectureId);
        chatMessageRepository.deleteByLectureId(lectureId);
        chatSessionRepository.deleteByLectureId(lectureId);
    }
}
