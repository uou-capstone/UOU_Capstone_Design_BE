package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseStudentItemDto;
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
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 강의실 멤버십(수강생 목록 / 제거 / 차단) 전용 서비스.
 *
 * 가입 요청(승인 대기) 흐름은 {@link CourseJoinRequestService} 가 담당하며, 이 클래스는
 * 이미 수강 중인 학생을 강의실에서 내보내거나(제거) 재가입까지 차단하는 작업만 다룬다.
 */
@Service
@RequiredArgsConstructor
public class CourseMembershipService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseJoinRequestRepository joinRequestRepository;
    private final StudentRepository studentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final NotificationService notificationService;
    private final CourseAuditLogger auditLogger;

    @Transactional(readOnly = true)
    public PageResponse<CourseStudentItemDto> listStudents(Long courseId, Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Course course = loadOwnedCourse(courseId);

        Page<Enrollment> page = enrollmentRepository.findByCourseIdWithStudentUser(course.getId(), pageable);
        return PageResponse.of(page.map(CourseStudentItemDto::new));
    }

    @Transactional
    public void removeStudent(Long courseId, Long studentId) {
        Course course = loadOwnedCourse(courseId);
        Enrollment enrollment = enrollmentRepository
                .findByCourseIdAndStudentIdWithUser(course.getId(), studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        Student student = enrollment.getStudent();
        enrollmentRepository.delete(enrollment);

        notificationService.notify(
                student.getUser(),
                NotificationType.COURSE_MEMBER_REMOVED,
                "강의실에서 제거됨",
                "%s 강의실에서 제거되었습니다. 필요 시 다시 가입 요청을 보낼 수 있습니다.".formatted(course.getTitle()),
                "course",
                course.getId()
        );

        auditLogger.studentRemoved(course.getId(), student.getId(), currentUserResolver.getUser().getId());
    }

    @Transactional
    public void blockStudent(Long courseId, Long studentId) {
        Course course = loadOwnedCourse(courseId);

        Student student = studentRepository.findByIdWithUser(studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 1) 활성 수강 관계가 있다면 끊는다.
        enrollmentRepository.findByCourseIdAndStudentIdWithUser(course.getId(), studentId)
                .ifPresent(enrollmentRepository::delete);

        // 2) 같은 학생의 PENDING 요청은 BLOCKED 로 정리하여 잔존 처리 방지.
        List<CourseJoinRequest> pendings = joinRequestRepository
                .findByStudentAndCourseAndStatus(student, course, CourseJoinRequestStatus.PENDING);
        for (CourseJoinRequest pending : pendings) {
            pending.block();
        }

        // 3) BLOCKED 마커가 없으면 생성. (existsByStudentAndCourseAndStatus(BLOCKED) 가 차단 검사 키)
        boolean alreadyBlocked = joinRequestRepository
                .existsByStudentAndCourseAndStatus(student, course, CourseJoinRequestStatus.BLOCKED);
        if (!alreadyBlocked && pendings.isEmpty()) {
            CourseJoinRequest blockMarker = CourseJoinRequest.builder()
                    .student(student)
                    .course(course)
                    .build();
            blockMarker.block();
            joinRequestRepository.save(blockMarker);
        }

        notificationService.notify(
                student.getUser(),
                NotificationType.COURSE_MEMBER_BLOCKED,
                "강의실에서 차단됨",
                "%s 강의실에서 차단되었습니다. 더 이상 같은 강의실에 가입 요청을 보낼 수 없습니다.".formatted(course.getTitle()),
                "course",
                course.getId()
        );

        auditLogger.studentBlocked(course.getId(), student.getId(), currentUserResolver.getUser().getId());
    }

    private Course loadOwnedCourse(Long courseId) {
        Teacher currentTeacher = currentUserResolver.getTeacher();
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return course;
    }
}
