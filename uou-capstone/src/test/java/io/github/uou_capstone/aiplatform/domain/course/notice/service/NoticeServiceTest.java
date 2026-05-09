package io.github.uou_capstone.aiplatform.domain.course.notice.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    @Mock private NoticeRepository noticeRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private CourseAccessService courseAccessService;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private NotificationService notificationService;

    @InjectMocks
    private NoticeService service;

    private Teacher teacher;
    private User teacherUser;
    private Course course;

    private Student studentActive;
    private User studentActiveUser;
    private Student studentInactive;
    private User studentInactiveUser;

    private static final Long COURSE_ID = 50L;

    @BeforeEach
    void setUp() {
        teacherUser = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 201L);
        teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);

        course = Course.builder().teacher(teacher).title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        studentActiveUser = User.builder().email("s1@x").password("p").fullName("alice").build();
        ReflectionTestUtils.setField(studentActiveUser, "id", 300L);
        studentActive = Student.builder().user(studentActiveUser).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(studentActive, "id", 100L);

        studentInactiveUser = User.builder().email("s2@x").password("p").fullName("bob").build();
        ReflectionTestUtils.setField(studentInactiveUser, "id", 301L);
        studentInactive = Student.builder().user(studentInactiveUser).grade(1).classNumber("1-2").build();
        ReflectionTestUtils.setField(studentInactive, "id", 101L);
    }

    @Test
    @DisplayName("createNotice: 본인 강의실에 작성 → ACTIVE 수강생 N명에게 알림 N개")
    void createNotice_publishes_notifications_to_active_enrollees() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);

        Notice persisted = Notice.builder()
                .course(course).author(teacher)
                .title("t").contentMarkdown("body")
                .category(NoticeCategory.GENERAL).priority(NoticePriority.NORMAL).pinned(false)
                .build();
        ReflectionTestUtils.setField(persisted, "id", 999L);
        when(noticeRepository.save(any(Notice.class))).thenReturn(persisted);

        // ACTIVE 학생 2명만 반환 — DROPPED/COMPLETED 는 제외
        Enrollment e1 = Enrollment.builder().student(studentActive).course(course).build();
        Enrollment e2 = Enrollment.builder().student(studentInactive).course(course).build();
        when(enrollmentRepository.findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE))
                .thenReturn(List.of(e1, e2));

        var dto = new NoticeCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "t");
        ReflectionTestUtils.setField(dto, "contentMarkdown", "body");

        service.createNotice(COURSE_ID, dto);

        // 두 학생 user 에게 NOTICE_PUBLISHED 알림이 1번씩
        verify(notificationService, times(2)).notify(
                any(User.class),
                eq(NotificationType.NOTICE_PUBLISHED),
                any(String.class),
                any(String.class),
                eq("NOTICE"),
                eq(999L));
    }

    @Test
    @DisplayName("createNotice: ACTIVE 수강생 0명 → 알림 미발송")
    void createNotice_no_active_no_notifications() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);

        Notice persisted = Notice.builder()
                .course(course).author(teacher).title("t").contentMarkdown("body")
                .build();
        ReflectionTestUtils.setField(persisted, "id", 1L);
        when(noticeRepository.save(any(Notice.class))).thenReturn(persisted);

        when(enrollmentRepository.findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE))
                .thenReturn(List.of());

        var dto = new NoticeCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "t");
        ReflectionTestUtils.setField(dto, "contentMarkdown", "body");

        service.createNotice(COURSE_ID, dto);

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createNotice: 타인 강의실 → courseAccessService 가 FORBIDDEN 던짐")
    void createNotice_forbidden_propagates() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        var dto = new NoticeCreateRequestDto();
        ReflectionTestUtils.setField(dto, "title", "t");
        ReflectionTestUtils.setField(dto, "contentMarkdown", "body");

        assertThatThrownBy(() -> service.createNotice(COURSE_ID, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(noticeRepository, never()).save(any());
        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("getNotice: 강의실 참가자가 아니면 FORBIDDEN")
    void getNotice_participant_check() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.getNotice(COURSE_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("getNotice: 다른 강의실의 noticeId 끼워넣기 → RESOURCE_NOT_FOUND")
    void getNotice_cross_course() {
        when(courseAccessService.loadCourseAsParticipant(COURSE_ID)).thenReturn(course);
        when(noticeRepository.findByIdAndCourse(999L, course))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.getNotice(COURSE_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
