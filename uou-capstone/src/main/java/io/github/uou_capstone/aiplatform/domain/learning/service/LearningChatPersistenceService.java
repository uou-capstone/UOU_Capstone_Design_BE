package io.github.uou_capstone.aiplatform.domain.learning.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.learning.dto.LearningChatMessageResponse;
import io.github.uou_capstone.aiplatform.domain.learning.dto.LearningChatSessionResponse;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessage;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatMessageRole;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatMessageRepository;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningChatSessionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LearningChatPersistenceService {

    private static final Set<String> SORT_WHITELIST = Set.of("lastMessageAt", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "lastMessageAt")
            .and(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final LearningChatSessionRepository chatSessionRepository;
    private final LearningChatMessageRepository chatMessageRepository;
    private final LectureRepository lectureRepository;

    @Transactional
    public LearningChatSession createSession(Long lectureId, User user) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
        LearningChatSession session = LearningChatSession.builder()
                .lecture(lecture)
                .user(user)
                .build();
        session.touch(LocalDateTime.now());
        return chatSessionRepository.save(session);
    }

    @Transactional
    public LearningChatSession getOrCreateActiveSession(Long lectureId, User user) {
        Optional<LearningChatSession> activeSession =
                chatSessionRepository.findFirstByUserIdAndLectureIdAndEndedAtIsNullOrderByLastMessageAtDescIdDesc(
                        user.getId(), lectureId);
        return activeSession.orElseGet(() -> createSession(lectureId, user));
    }

    @Transactional(readOnly = true)
    public LearningChatSession getOwnedSession(Long sessionId, Long userId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public LearningChatSession getOwnedSession(Long sessionId, Long userId, Long lectureId) {
        return chatSessionRepository.findByIdAndUserIdAndLectureId(sessionId, userId, lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public LearningChatSession getOwnedActiveSession(Long sessionId, Long userId, Long lectureId) {
        return chatSessionRepository.findByIdAndUserIdAndLectureIdAndEndedAtIsNull(sessionId, userId, lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
    }

    @Transactional
    public void saveUserMessage(Long sessionId, Long userId, Long lectureId, String content, Integer pageNumber) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        LearningChatSession session = getOwnedSession(sessionId, userId, lectureId);
        LocalDateTime now = LocalDateTime.now();
        session.setTitleIfBlank(content.trim());
        session.touch(now);
        chatMessageRepository.save(LearningChatMessage.builder()
                .chatSession(session)
                .role(LearningChatMessageRole.USER)
                .content(content.trim())
                .pageNumber(pageNumber)
                .build());
    }

    @Transactional
    public void saveAssistantMessage(Long sessionId, Long userId, Long lectureId, String content, Integer pageNumber) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        LearningChatSession session = getOwnedSession(sessionId, userId, lectureId);
        session.touch(LocalDateTime.now());
        chatMessageRepository.save(LearningChatMessage.builder()
                .chatSession(session)
                .role(LearningChatMessageRole.ASSISTANT)
                .content(content.trim())
                .pageNumber(pageNumber)
                .build());
    }

    @Transactional
    public void markEnded(Long sessionId, Long userId, Long lectureId) {
        LearningChatSession session = getOwnedSession(sessionId, userId, lectureId);
        session.markEnded(LocalDateTime.now());
    }

    @Transactional
    public int endActiveSessionsByLecture(Long lectureId) {
        return chatSessionRepository.endActiveSessionsByLecture(lectureId, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public PageResponse<LearningChatSessionResponse> getSessions(Long lectureId, Long userId, Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<LearningChatSession> page = chatSessionRepository.findByUserIdAndLectureId(userId, lectureId, pageable);
        return PageResponse.of(page.map(LearningChatSessionResponse::new));
    }

    @Transactional(readOnly = true)
    public List<LearningChatMessageResponse> getMessages(Long sessionId, Long userId) {
        LearningChatSession session = getOwnedSession(sessionId, userId);
        return chatMessageRepository.findByChatSessionIdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .map(LearningChatMessageResponse::new)
                .toList();
    }
}
