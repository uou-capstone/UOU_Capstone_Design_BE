package io.github.uou_capstone.aiplatform.domain.course.notice.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeComment;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeCommentRepository;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeCommentServiceTest {

    @Mock private NoticeCommentRepository commentRepository;
    @Mock private NoticeRepository noticeRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private NoticeCommentService service;

    private User parentAuthor;
    private User otherUser;
    private Course course;
    private Notice notice;
    private NoticeComment topLevelComment;
    private NoticeComment replyComment;

    private static final Long COURSE_ID = 50L;
    private static final Long NOTICE_ID = 99L;

    @BeforeEach
    void setUp() {
        parentAuthor = User.builder().email("p@x").password("p").fullName("parent").build();
        ReflectionTestUtils.setField(parentAuthor, "id", 1000L);

        otherUser = User.builder().email("o@x").password("p").fullName("other").build();
        ReflectionTestUtils.setField(otherUser, "id", 1001L);

        User teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 201L);
        Teacher teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        notice = Notice.builder().course(course).author(teacher).title("t").contentMarkdown("b").build();
        ReflectionTestUtils.setField(notice, "id", NOTICE_ID);

        topLevelComment = NoticeComment.builder()
                .notice(notice).author(parentAuthor).contentMarkdown("parent body").build();
        ReflectionTestUtils.setField(topLevelComment, "id", 5000L);

        replyComment = NoticeComment.builder()
                .notice(notice).author(parentAuthor).parentComment(topLevelComment)
                .contentMarkdown("reply body").build();
        ReflectionTestUtils.setField(replyComment, "id", 5001L);
    }

    @Test
    @DisplayName("createComment: 답글 작성 시 parent 작성자에게 알림 (본인이 아닌 경우)")
    void createComment_reply_notifies_parent_author() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(noticeRepository.findByIdAndCourse(NOTICE_ID, course)).thenReturn(Optional.of(notice));
        when(currentUserResolver.getUser()).thenReturn(otherUser); // 답글 작성자는 parent 와 다름
        when(commentRepository.findByIdAndNotice(eq(topLevelComment.getId()), eq(notice)))
                .thenReturn(Optional.of(topLevelComment));

        NoticeComment savedReply = NoticeComment.builder()
                .notice(notice).author(otherUser).parentComment(topLevelComment).contentMarkdown("hi").build();
        ReflectionTestUtils.setField(savedReply, "id", 6000L);
        when(commentRepository.save(any(NoticeComment.class))).thenReturn(savedReply);

        var dto = new NoticeCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "hi");
        ReflectionTestUtils.setField(dto, "parentCommentId", topLevelComment.getId());

        service.createComment(COURSE_ID, NOTICE_ID, dto);

        // parent 작성자에게 NOTICE_COMMENT_REPLIED 알림 발송 검증
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(
                eq(parentAuthor),
                eq(NotificationType.NOTICE_COMMENT_REPLIED),
                any(String.class),
                bodyCaptor.capture(),
                eq("NOTICE"),
                eq(NOTICE_ID));
        // body 가 currentUser.fullName + ": " + summary 형태인지
        assertThat(bodyCaptor.getValue()).startsWith("other: ");
    }

    @Test
    @DisplayName("createComment: 본인이 본인 댓글에 답글 → 알림 미발송")
    void createComment_self_reply_no_notification() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(noticeRepository.findByIdAndCourse(NOTICE_ID, course)).thenReturn(Optional.of(notice));
        when(currentUserResolver.getUser()).thenReturn(parentAuthor); // 답글 작성자 = parent 작성자
        when(commentRepository.findByIdAndNotice(eq(topLevelComment.getId()), eq(notice)))
                .thenReturn(Optional.of(topLevelComment));
        when(commentRepository.save(any(NoticeComment.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = new NoticeCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "self reply");
        ReflectionTestUtils.setField(dto, "parentCommentId", topLevelComment.getId());

        service.createComment(COURSE_ID, NOTICE_ID, dto);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createComment: 답글의 답글 시도 → INVALID_PARAMETER (1단계 제약)")
    void createComment_reply_to_reply_rejected() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(noticeRepository.findByIdAndCourse(NOTICE_ID, course)).thenReturn(Optional.of(notice));
        when(currentUserResolver.getUser()).thenReturn(otherUser);
        when(commentRepository.findByIdAndNotice(eq(replyComment.getId()), eq(notice)))
                .thenReturn(Optional.of(replyComment));

        var dto = new NoticeCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "x");
        ReflectionTestUtils.setField(dto, "parentCommentId", replyComment.getId()); // 답글에 답글

        assertThatThrownBy(() -> service.createComment(COURSE_ID, NOTICE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("createComment: 다른 게시글의 댓글 ID 를 parent 로 → RESOURCE_NOT_FOUND")
    void createComment_parent_from_other_notice_rejected() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(noticeRepository.findByIdAndCourse(NOTICE_ID, course)).thenReturn(Optional.of(notice));
        // findByIdAndNotice 가 빈 Optional 반환 — 다른 notice 의 댓글이라 같은 notice 에서 찾을 수 없음
        when(commentRepository.findByIdAndNotice(eq(7777L), eq(notice)))
                .thenReturn(Optional.empty());

        var dto = new NoticeCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "x");
        ReflectionTestUtils.setField(dto, "parentCommentId", 7777L);

        assertThatThrownBy(() -> service.createComment(COURSE_ID, NOTICE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
