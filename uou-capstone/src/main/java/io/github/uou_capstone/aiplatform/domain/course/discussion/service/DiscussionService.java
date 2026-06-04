package io.github.uou_capstone.aiplatform.domain.course.discussion.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionListItemResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionUpdateRequestDto;
import io.github.uou_capstone.aiplatform.common.util.NotificationBodyFormatter;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class DiscussionService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt", "pinned");
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("pinned"),
            Sort.Order.desc("createdAt")
    );

    private static final int BODY_SUMMARY_LEN = 100;

    private final DiscussionRepository discussionRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;
    private final TeacherNotificationPublisher teacherNotificationPublisher;

    @Transactional
    public DiscussionResponseDto createDiscussion(Long courseId, DiscussionCreateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        User currentUser = currentUserResolver.getUser();

        Discussion saved = discussionRepository.save(
                Discussion.builder()
                        .course(course)
                        .author(currentUser)
                        .title(dto.getTitle())
                        .contentMarkdown(dto.getContentMarkdown())
                        .category(dto.getCategory())
                        .pinned(dto.getPinned())
                        .allowComments(dto.getAllowComments())
                        .build()
        );

        // 담당 교사 알림 — 본인이 작성자면 Publisher 내부에서 자기 작업 분기로 변환
        teacherNotificationPublisher.notifyCourseTeacher(
                course,
                currentUser,
                NotificationType.DISCUSSION_CREATED,
                "새 토론 게시글",
                currentUser.getFullName() + ": "
                        + NotificationBodyFormatter.summarize(dto.getTitle(), BODY_SUMMARY_LEN),
                "DISCUSSION",
                saved.getId()
        );

        return new DiscussionResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DiscussionListItemResponseDto> listDiscussions(Long courseId, Pageable rawPageable) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<Discussion> page = discussionRepository.findByCourse(course, pageable);
        return PageResponse.of(page.map(DiscussionListItemResponseDto::new));
    }

    /**
     * 상세 조회 — viewCount +1 (별도 트랜잭션이 아닌 같은 트랜잭션 내 UPDATE).
     * Modifying 쿼리 후 @Query clearAutomatically=true 로 1차 캐시 무효화 → 재조회 시 최신 viewCount 반영.
     */
    @Transactional
    public DiscussionResponseDto getDiscussion(Long courseId, Long discussionId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        // viewCount 증가는 존재 검증 후
        Discussion existing = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        discussionRepository.incrementViewCount(existing.getId());
        // clearAutomatically=true 가 1차 캐시를 비웠으므로 재조회
        Discussion fresh = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new DiscussionResponseDto(fresh);
    }

    @Transactional
    public DiscussionResponseDto updateDiscussion(Long courseId, Long discussionId, DiscussionUpdateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion d = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        // 수정은 작성자만
        courseAccessService.ensureAuthor(currentUser, d.getAuthor().getId());

        d.update(dto.getTitle(),
                dto.getContentMarkdown(),
                dto.getCategory(),
                dto.getPinned(),
                dto.getAllowComments());
        return new DiscussionResponseDto(d);
    }

    @Transactional
    public void deleteDiscussion(Long courseId, Long discussionId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion d = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        // 삭제는 작성자 또는 강의실 교사
        courseAccessService.ensureAuthorOrCourseTeacher(currentUser, course, d.getAuthor().getId());

        discussionRepository.delete(d);
    }
}
