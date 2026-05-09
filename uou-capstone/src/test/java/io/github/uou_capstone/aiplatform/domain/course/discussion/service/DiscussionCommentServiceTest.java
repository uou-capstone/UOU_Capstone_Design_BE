package io.github.uou_capstone.aiplatform.domain.course.discussion.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionComment;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionCommentRepository;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscussionCommentServiceTest {

    @Mock private DiscussionCommentRepository commentRepository;
    @Mock private DiscussionRepository discussionRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private DiscussionCommentService service;

    private User author;
    private User commenter;
    private Course course;
    private Discussion discussion;
    private Discussion noCommentsDiscussion;

    private static final Long COURSE_ID = 50L;
    private static final Long DISCUSSION_ID = 100L;
    private static final Long NO_COMMENTS_DISCUSSION_ID = 101L;

    @BeforeEach
    void setUp() {
        author = User.builder().email("a@x").password("p").fullName("alice").build();
        ReflectionTestUtils.setField(author, "id", 200L);

        commenter = User.builder().email("c@x").password("p").fullName("bob").build();
        ReflectionTestUtils.setField(commenter, "id", 201L);

        User teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 300L);
        Teacher teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        discussion = Discussion.builder()
                .course(course).author(author)
                .title("t").contentMarkdown("body")
                .category(DiscussionCategory.QUESTION).pinned(false).allowComments(true)
                .build();
        ReflectionTestUtils.setField(discussion, "id", DISCUSSION_ID);

        noCommentsDiscussion = Discussion.builder()
                .course(course).author(author)
                .title("nc").contentMarkdown("x")
                .category(DiscussionCategory.FREE).pinned(false).allowComments(false)
                .build();
        ReflectionTestUtils.setField(noCommentsDiscussion, "id", NO_COMMENTS_DISCUSSION_ID);
    }

    @Test
    @DisplayName("createComment: 다른 사용자 일반 댓글 → 게시글 작성자에게 알림")
    void createComment_top_level_notifies_post_author() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(DISCUSSION_ID, course))
                .thenReturn(Optional.of(discussion));
        when(currentUserResolver.getUser()).thenReturn(commenter);
        when(commentRepository.save(any(DiscussionComment.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = new DiscussionCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "nice post");

        service.createComment(COURSE_ID, DISCUSSION_ID, dto);

        verify(notificationService).notify(
                eq(author),
                eq(NotificationType.DISCUSSION_COMMENT_RECEIVED),
                any(String.class),
                any(String.class),
                eq("DISCUSSION"),
                eq(DISCUSSION_ID));
    }

    @Test
    @DisplayName("createComment: 본인 게시글에 본인 댓글 → 알림 미발송")
    void createComment_self_comment_no_notification() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(DISCUSSION_ID, course))
                .thenReturn(Optional.of(discussion));
        when(currentUserResolver.getUser()).thenReturn(author);
        when(commentRepository.save(any(DiscussionComment.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = new DiscussionCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "self");

        service.createComment(COURSE_ID, DISCUSSION_ID, dto);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createComment: allowComments=false 게시글 → INVALID_PARAMETER")
    void createComment_disabled_rejected() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(NO_COMMENTS_DISCUSSION_ID, course))
                .thenReturn(Optional.of(noCommentsDiscussion));

        var dto = new DiscussionCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "x");

        assertThatThrownBy(() -> service.createComment(COURSE_ID, NO_COMMENTS_DISCUSSION_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);

        verify(commentRepository, never()).save(any());
    }

    @Test
    @DisplayName("createComment: 답글의 답글 → INVALID_PARAMETER")
    void createComment_reply_to_reply_rejected() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(DISCUSSION_ID, course))
                .thenReturn(Optional.of(discussion));
        when(currentUserResolver.getUser()).thenReturn(commenter);

        DiscussionComment topLevel = DiscussionComment.builder()
                .discussion(discussion).author(author).contentMarkdown("top").build();
        ReflectionTestUtils.setField(topLevel, "id", 5000L);
        DiscussionComment reply = DiscussionComment.builder()
                .discussion(discussion).author(commenter).parentComment(topLevel).contentMarkdown("reply").build();
        ReflectionTestUtils.setField(reply, "id", 5001L);

        when(commentRepository.findByIdAndDiscussion(5001L, discussion)).thenReturn(Optional.of(reply));

        var dto = new DiscussionCommentCreateRequestDto();
        ReflectionTestUtils.setField(dto, "contentMarkdown", "x");
        ReflectionTestUtils.setField(dto, "parentCommentId", 5001L);

        assertThatThrownBy(() -> service.createComment(COURSE_ID, DISCUSSION_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INVALID_PARAMETER);
    }
}
