package io.github.uou_capstone.aiplatform.domain.course.discussion.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.util.NotificationBodyFormatter;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionComment;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionCommentRepository;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
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
public class DiscussionCommentService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "createdAt");

    private static final int BODY_SUMMARY_LEN = 100;
    private static final String RESOURCE_TYPE = "DISCUSSION";

    private final DiscussionCommentRepository commentRepository;
    private final DiscussionRepository discussionRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;
    private final NotificationService notificationService;

    @Transactional
    public DiscussionCommentResponseDto createComment(Long courseId,
                                                      Long discussionId,
                                                      DiscussionCommentCreateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion discussion = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        if (!discussion.isAllowComments()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "댓글이 비활성화된 게시글입니다.");
        }

        User currentUser = currentUserResolver.getUser();

        DiscussionComment parent = null;
        if (dto.getParentCommentId() != null) {
            // 같은 discussion 의 댓글인지 검증 (다른 게시글 댓글 차단)
            parent = commentRepository.findByIdAndDiscussion(dto.getParentCommentId(), discussion)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            if (parent.getParentComment() != null) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "대댓글의 답글은 허용되지 않습니다.");
            }
        }

        DiscussionComment saved = commentRepository.save(
                DiscussionComment.builder()
                        .discussion(discussion)
                        .author(currentUser)
                        .parentComment(parent)
                        .contentMarkdown(dto.getContentMarkdown())
                        .build()
        );

        // 알림: 답글이면 parent 작성자에게, 일반 댓글이면 게시글 작성자에게.
        // 본인이 본인 글/댓글에 댓글 다는 경우 알림 스킵.
        // Discussion 도메인은 동일 enum DISCUSSION_COMMENT_RECEIVED 사용 — 답글/일반 구분은 title/body 로.
        User notifyTarget = parent != null ? parent.getAuthor() : discussion.getAuthor();
        String title = parent != null ? "내 댓글에 답글" : "내 게시글에 댓글";

        if (!notifyTarget.getId().equals(currentUser.getId())) {
            String body = currentUser.getFullName() + ": "
                    + NotificationBodyFormatter.summarize(dto.getContentMarkdown(), BODY_SUMMARY_LEN);
            notificationService.notify(
                    notifyTarget,
                    NotificationType.DISCUSSION_COMMENT_RECEIVED,
                    title,
                    body,
                    RESOURCE_TYPE,
                    discussion.getId()
            );
        }

        return new DiscussionCommentResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DiscussionCommentResponseDto> listComments(Long courseId,
                                                                   Long discussionId,
                                                                   Pageable rawPageable) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion discussion = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<DiscussionComment> page = commentRepository.findByDiscussion(discussion, pageable);
        return PageResponse.of(page.map(DiscussionCommentResponseDto::new));
    }

    @Transactional
    public DiscussionCommentResponseDto updateComment(Long courseId,
                                                      Long discussionId,
                                                      Long commentId,
                                                      DiscussionCommentUpdateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion discussion = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        DiscussionComment comment = commentRepository.findByIdAndDiscussion(commentId, discussion)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthor(currentUser, comment.getAuthor().getId());

        comment.updateContent(dto.getContentMarkdown());
        return new DiscussionCommentResponseDto(comment);
    }

    @Transactional
    public void deleteComment(Long courseId, Long discussionId, Long commentId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Discussion discussion = discussionRepository.findByIdAndCourse(discussionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        DiscussionComment comment = commentRepository.findByIdAndDiscussion(commentId, discussion)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthorOrCourseTeacher(currentUser, course, comment.getAuthor().getId());

        commentRepository.delete(comment);
    }
}
