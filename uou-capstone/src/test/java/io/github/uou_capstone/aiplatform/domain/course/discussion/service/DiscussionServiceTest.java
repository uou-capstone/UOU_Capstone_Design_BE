package io.github.uou_capstone.aiplatform.domain.course.discussion.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionCategory;
import io.github.uou_capstone.aiplatform.domain.course.discussion.repository.DiscussionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscussionServiceTest {

    @Mock private DiscussionRepository discussionRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private TeacherNotificationPublisher teacherNotificationPublisher;

    @InjectMocks
    private DiscussionService service;

    private User authorUser;
    private User otherUser;
    private User teacherUser;
    private Teacher teacher;
    private Course course;
    private Discussion discussion;

    private static final Long COURSE_ID = 50L;
    private static final Long DISCUSSION_ID = 100L;

    @BeforeEach
    void setUp() {
        authorUser = User.builder().email("a@x").password("p").fullName("alice").build();
        ReflectionTestUtils.setField(authorUser, "id", 200L);

        otherUser = User.builder().email("o@x").password("p").fullName("bob").build();
        ReflectionTestUtils.setField(otherUser, "id", 201L);

        teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 300L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);
        ReflectionTestUtils.setField(teacherUser, "teacher", teacher);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        discussion = Discussion.builder()
                .course(course).author(authorUser)
                .title("t").contentMarkdown("body")
                .category(DiscussionCategory.QUESTION).pinned(false).allowComments(true)
                .build();
        ReflectionTestUtils.setField(discussion, "id", DISCUSSION_ID);
    }

    @Test
    @DisplayName("createDiscussion: 참가자 검증 통과 시 작성 성공")
    void createDiscussion_success() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getUser()).thenReturn(authorUser);
        when(discussionRepository.save(any(Discussion.class))).thenAnswer(inv -> {
            Discussion d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", 999L);
            return d;
        });

        var dto = new DiscussionCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "hello");
        ReflectionTestUtils.setField(dto, "contentMarkdown", "body");
        ReflectionTestUtils.setField(dto, "category", DiscussionCategory.FREE);

        var result = service.createDiscussion(COURSE_ID, dto);

        assertThat(result.getTitle()).isEqualTo("hello");
        assertThat(result.getCategory()).isEqualTo(DiscussionCategory.FREE);
    }

    @Test
    @DisplayName("createDiscussion: 비참가자(DROPPED 학생) → CourseAccessService 가 FORBIDDEN")
    void createDiscussion_non_participant_forbidden() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        var dto = new DiscussionCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "t");
        ReflectionTestUtils.setField(dto, "contentMarkdown", "b");

        assertThatThrownBy(() -> service.createDiscussion(COURSE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(discussionRepository, never()).save(any());
    }

    @Test
    @DisplayName("getDiscussion: viewCount 증가 호출됨")
    void getDiscussion_increments_viewCount() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(DISCUSSION_ID, course))
                .thenReturn(Optional.of(discussion));

        service.getDiscussion(COURSE_ID, DISCUSSION_ID);

        verify(discussionRepository).incrementViewCount(DISCUSSION_ID);
    }

    @Test
    @DisplayName("updateDiscussion: 작성자 아닌 사용자 → FORBIDDEN")
    void updateDiscussion_non_author_forbidden() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(DISCUSSION_ID, course))
                .thenReturn(Optional.of(discussion));
        when(currentUserResolver.getUser()).thenReturn(otherUser);

        // ensureAuthor 가 FORBIDDEN 던지도록
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(courseAccessService).ensureAuthor(otherUser, authorUser.getId());

        var dto = new DiscussionUpdateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "new");

        assertThatThrownBy(() -> service.updateDiscussion(COURSE_ID, DISCUSSION_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("getDiscussion: 다른 강의실 discussionId → RESOURCE_NOT_FOUND")
    void getDiscussion_cross_course() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(discussionRepository.findByIdAndCourse(999L, course)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDiscussion(COURSE_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
