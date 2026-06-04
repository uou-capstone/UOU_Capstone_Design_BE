package io.github.uou_capstone.aiplatform.domain.course.notice.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.util.NotificationBodyFormatter;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeComment;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeCommentRepository;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
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
public class NoticeCommentService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "createdAt");

    private static final int BODY_SUMMARY_LEN = 100;
    private static final String RESOURCE_TYPE = "NOTICE";

    private final NoticeCommentRepository commentRepository;
    private final NoticeRepository noticeRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;
    private final NotificationService notificationService;
    private final TeacherNotificationPublisher teacherNotificationPublisher;

    @Transactional
    public NoticeCommentResponseDto createComment(Long courseId,
                                                  Long noticeId,
                                                  NoticeCommentCreateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        NoticeComment parent = null;
        if (dto.getParentCommentId() != null) {
            // 같은 notice 의 댓글인지 검증 (다른 게시글 댓글 ID 차단)
            parent = commentRepository.findByIdAndNotice(dto.getParentCommentId(), notice)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            // 1단계 답글만 허용
            if (parent.getParentComment() != null) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "대댓글의 답글은 허용되지 않습니다.");
            }
        }

        NoticeComment saved = commentRepository.save(
                NoticeComment.builder()
                        .notice(notice)
                        .author(currentUser)
                        .parentComment(parent)
                        .contentMarkdown(dto.getContentMarkdown())
                        .build()
        );

        // 답글이고 부모 작성자가 본인이 아니면 답글 알림 발송
        Long parentAuthorUserId = null;
        if (parent != null) {
            User parentAuthor = parent.getAuthor();
            parentAuthorUserId = parentAuthor.getId();
            if (!parentAuthor.getId().equals(currentUser.getId())) {
                String body = currentUser.getFullName() + ": "
                        + NotificationBodyFormatter.summarize(dto.getContentMarkdown(), BODY_SUMMARY_LEN);
                notificationService.notify(
                        parentAuthor,
                        NotificationType.NOTICE_COMMENT_REPLIED,
                        "내 댓글에 답글",
                        body,
                        RESOURCE_TYPE,
                        notice.getId()
                );
            }
        }

        // 강의실 담당 교사 알림 — 위 답글 알림이 이미 담당 교사에게 갔다면 중복 방지
        Long teacherUserId = course.getTeacher() != null && course.getTeacher().getUser() != null
                ? course.getTeacher().getUser().getId() : null;
        boolean alreadyNotifiedTeacher = parentAuthorUserId != null
                && teacherUserId != null
                && !parentAuthorUserId.equals(currentUser.getId())
                && parentAuthorUserId.equals(teacherUserId);
        if (!alreadyNotifiedTeacher) {
            String teacherBody = currentUser.getFullName() + ": "
                    + NotificationBodyFormatter.summarize(dto.getContentMarkdown(), BODY_SUMMARY_LEN);
            teacherNotificationPublisher.notifyCourseTeacher(
                    course,
                    currentUser,
                    NotificationType.NOTICE_COMMENTED,
                    "공지 새 댓글",
                    teacherBody,
                    RESOURCE_TYPE,
                    notice.getId()
            );
        }

        return new NoticeCommentResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<NoticeCommentResponseDto> listComments(Long courseId,
                                                               Long noticeId,
                                                               Pageable rawPageable) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<NoticeComment> page = commentRepository.findByNotice(notice, pageable);
        return PageResponse.of(page.map(NoticeCommentResponseDto::new));
    }

    @Transactional
    public NoticeCommentResponseDto updateComment(Long courseId,
                                                  Long noticeId,
                                                  Long commentId,
                                                  NoticeCommentUpdateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        NoticeComment comment = commentRepository.findByIdAndNotice(commentId, notice)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthor(currentUser, comment.getAuthor().getId());

        comment.updateContent(dto.getContentMarkdown());
        return new NoticeCommentResponseDto(comment);
    }

    @Transactional
    public void deleteComment(Long courseId, Long noticeId, Long commentId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        NoticeComment comment = commentRepository.findByIdAndNotice(commentId, notice)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthorOrCourseTeacher(
                currentUser, course, comment.getAuthor().getId());

        commentRepository.delete(comment);
    }
}
