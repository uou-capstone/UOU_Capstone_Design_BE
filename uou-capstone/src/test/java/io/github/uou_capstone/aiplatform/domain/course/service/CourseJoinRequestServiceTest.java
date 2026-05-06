package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseJoinRequestRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.service.DistributedLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseJoinRequestServiceTest {

    @Mock private CourseJoinRequestRepository joinRequestRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private CurrentUserResolver currentUserResolver;
    @Mock private NotificationService notificationService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private CourseAuditLogger auditLogger;

    @InjectMocks
    private CourseJoinRequestService service;

    private Student student;
    private Teacher teacherOwner;
    private User studentUser;
    private User teacherUser;
    private Course course;

    private static final String INVITATION_CODE = "code-1";

    @BeforeEach
    void setUp() {
        teacherUser = User.builder().email("teacher@example.com").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(teacherUser, "id", 201L);

        teacherOwner = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        ReflectionTestUtils.setField(teacherOwner, "id", 10L);

        studentUser = User.builder().email("stu@example.com").password("p").fullName("stu").build();
        ReflectionTestUtils.setField(studentUser, "id", 200L);

        student = Student.builder().user(studentUser).grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 100L);

        course = Course.builder()
                .teacher(teacherOwner)
                .title("course")
                .description("d")
                .invitationCode(INVITATION_CODE)
                .build();
        ReflectionTestUtils.setField(course, "id", 50L);
    }

    /** Lock 과 Transaction 을 즉시 실행하도록 설정. */
    @SuppressWarnings("unchecked")
    private void primeLockAndTransactionToRunInline() {
        when(distributedLockService.executeWithLock(anyString(), anyLong(), anyLong(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(3)).get());
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    @Test
    void createJoinRequest_savesPending_whenNoExisting() {
        primeLockAndTransactionToRunInline();
        when(currentUserResolver.getStudent()).thenReturn(student);
        when(courseRepository.findByInvitationCode(INVITATION_CODE)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.BLOCKED)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.PENDING)).thenReturn(false);

        CourseJoinRequest saved = CourseJoinRequest.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(saved, "id", 1L);
        when(joinRequestRepository.save(any(CourseJoinRequest.class))).thenReturn(saved);

        CourseJoinRequestResponseDto response = service.createJoinRequest(
                new CourseJoinRequestCreateDto(INVITATION_CODE));

        assertThat(response).isNotNull();
        verify(joinRequestRepository).save(any(CourseJoinRequest.class));
        verify(distributedLockService).executeWithLock(eq("course-join-request:100:50"), anyLong(), anyLong(), any(Supplier.class));
    }

    @Test
    void createJoinRequest_throwsWhenAlreadyEnrolled() {
        primeLockAndTransactionToRunInline();
        when(currentUserResolver.getStudent()).thenReturn(student);
        when(courseRepository.findByInvitationCode(INVITATION_CODE)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(true);

        assertThatThrownBy(() -> service.createJoinRequest(new CourseJoinRequestCreateDto(INVITATION_CODE)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.ENROLLMENT_ALREADY_EXISTS));
        verify(joinRequestRepository, never()).save(any());
    }

    @Test
    void createJoinRequest_throwsWhenBlocked() {
        primeLockAndTransactionToRunInline();
        when(currentUserResolver.getStudent()).thenReturn(student);
        when(courseRepository.findByInvitationCode(INVITATION_CODE)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.BLOCKED)).thenReturn(true);

        assertThatThrownBy(() -> service.createJoinRequest(new CourseJoinRequestCreateDto(INVITATION_CODE)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.JOIN_REQUEST_BLOCKED));
        verify(joinRequestRepository, never()).save(any());
    }

    @Test
    void createJoinRequest_throwsWhenPendingExists() {
        primeLockAndTransactionToRunInline();
        when(currentUserResolver.getStudent()).thenReturn(student);
        when(courseRepository.findByInvitationCode(INVITATION_CODE)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.BLOCKED)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.PENDING)).thenReturn(true);

        assertThatThrownBy(() -> service.createJoinRequest(new CourseJoinRequestCreateDto(INVITATION_CODE)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.JOIN_REQUEST_PENDING_EXISTS));
        verify(joinRequestRepository, never()).save(any());
    }

    @Test
    void createJoinRequest_allowsNewPendingAfterRejected() {
        // REJECTED 상태는 BLOCKED/PENDING 어디에도 잡히지 않으므로 신규 PENDING 허용
        primeLockAndTransactionToRunInline();
        when(currentUserResolver.getStudent()).thenReturn(student);
        when(courseRepository.findByInvitationCode(INVITATION_CODE)).thenReturn(Optional.of(course));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.BLOCKED)).thenReturn(false);
        when(joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.PENDING)).thenReturn(false);

        CourseJoinRequest saved = CourseJoinRequest.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(saved, "id", 2L);
        when(joinRequestRepository.save(any(CourseJoinRequest.class))).thenReturn(saved);

        service.createJoinRequest(new CourseJoinRequestCreateDto(INVITATION_CODE));

        verify(joinRequestRepository).save(any(CourseJoinRequest.class));
    }

    @Test
    void approveJoinRequest_createsEnrollmentAndNotifies() {
        when(currentUserResolver.getTeacher()).thenReturn(teacherOwner);
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(50L)).thenReturn(Optional.of(course));

        CourseJoinRequest pending = CourseJoinRequest.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(pending, "id", 1L);
        when(joinRequestRepository.findByIdAndCourseId(1L, 50L)).thenReturn(Optional.of(pending));
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);

        service.approveJoinRequest(50L, 1L);

        ArgumentCaptor<Enrollment> enrollCaptor = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollmentRepository).save(enrollCaptor.capture());
        assertThat(enrollCaptor.getValue().getStudent()).isEqualTo(student);
        assertThat(enrollCaptor.getValue().getCourse()).isEqualTo(course);

        assertThat(pending.getStatus()).isEqualTo(CourseJoinRequestStatus.APPROVED);
        verify(notificationService).notify(eq(studentUser),
                eq(NotificationType.COURSE_JOIN_APPROVED),
                anyString(), anyString(), eq("course"), eq(50L));
    }

    @Test
    void rejectJoinRequest_marksRejectedAndNotifies() {
        when(currentUserResolver.getTeacher()).thenReturn(teacherOwner);
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(50L)).thenReturn(Optional.of(course));

        CourseJoinRequest pending = CourseJoinRequest.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(pending, "id", 1L);
        when(joinRequestRepository.findByIdAndCourseId(1L, 50L)).thenReturn(Optional.of(pending));

        service.rejectJoinRequest(50L, 1L);

        assertThat(pending.getStatus()).isEqualTo(CourseJoinRequestStatus.REJECTED);
        verify(enrollmentRepository, never()).save(any());
        verify(notificationService, times(1))
                .notify(eq(studentUser), eq(NotificationType.COURSE_JOIN_REJECTED),
                        anyString(), anyString(), eq("course"), eq(50L));
    }

    @Test
    void blockJoinRequest_marksBlockedAndNotifies() {
        when(currentUserResolver.getTeacher()).thenReturn(teacherOwner);
        when(currentUserResolver.getUser()).thenReturn(teacherUser);
        when(courseRepository.findById(50L)).thenReturn(Optional.of(course));

        CourseJoinRequest pending = CourseJoinRequest.builder().student(student).course(course).build();
        ReflectionTestUtils.setField(pending, "id", 1L);
        when(joinRequestRepository.findByIdAndCourseId(1L, 50L)).thenReturn(Optional.of(pending));

        service.blockJoinRequest(50L, 1L);

        assertThat(pending.getStatus()).isEqualTo(CourseJoinRequestStatus.BLOCKED);
        verify(enrollmentRepository, never()).save(any());
        verify(notificationService, times(1))
                .notify(eq(studentUser), eq(NotificationType.COURSE_JOIN_BLOCKED),
                        anyString(), anyString(), eq("course"), eq(50L));
    }
}
