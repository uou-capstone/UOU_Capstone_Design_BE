package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestListItemDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.MyJoinRequestItemDto;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class CourseJoinRequestService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final CourseJoinRequestRepository joinRequestRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 가입 요청 생성. (student, course) 단위 분산 락으로 동시 요청을 직렬화한다.
     * 락 → 트랜잭션 → 재검증(Enrollment / BLOCKED / PENDING) 순서로 구성하여,
     * 트랜잭션이 락 구간 안에서 commit 까지 끝나도록 한다.
     */
    public CourseJoinRequestResponseDto createJoinRequest(CourseJoinRequestCreateDto dto) {
        Student student = currentUserResolver.getStudent();
        Course course = courseRepository.findByInvitationCode(dto.getInvitationCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INVITATION_CODE));

        String lockKey = "course-join-request:" + student.getId() + ":" + course.getId();
        return distributedLockService.executeWithLock(lockKey, 3, 5, () ->
                transactionTemplate.execute(status -> persistPendingJoinRequest(student, course))
        );
    }

    private CourseJoinRequestResponseDto persistPendingJoinRequest(Student student, Course course) {
        if (enrollmentRepository.existsByStudentAndCourse(student, course)) {
            throw new BusinessException(CommonErrorCode.ENROLLMENT_ALREADY_EXISTS);
        }

        if (joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.BLOCKED)) {
            throw new BusinessException(CommonErrorCode.JOIN_REQUEST_BLOCKED);
        }

        if (joinRequestRepository.existsByStudentAndCourseAndStatus(
                student, course, CourseJoinRequestStatus.PENDING)) {
            throw new BusinessException(CommonErrorCode.JOIN_REQUEST_PENDING_EXISTS);
        }

        CourseJoinRequest saved = joinRequestRepository.save(
                CourseJoinRequest.builder()
                        .student(student)
                        .course(course)
                        .build()
        );
        return new CourseJoinRequestResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<MyJoinRequestItemDto> getMyJoinRequests(Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Student student = currentUserResolver.getStudent();

        Page<CourseJoinRequest> page = joinRequestRepository
                .findByStudentIdWithCourse(student.getId(), pageable);

        return PageResponse.of(page.map(MyJoinRequestItemDto::new));
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseJoinRequestListItemDto> getJoinRequests(Long courseId,
                                                                      CourseJoinRequestStatus status,
                                                                      Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Course course = loadOwnedCourse(courseId);

        CourseJoinRequestStatus targetStatus = status != null ? status : CourseJoinRequestStatus.PENDING;

        Page<CourseJoinRequest> page = joinRequestRepository
                .findByCourseIdAndStatusWithStudent(course.getId(), targetStatus, pageable);

        return PageResponse.of(page.map(CourseJoinRequestListItemDto::new));
    }

    @Transactional
    public void approveJoinRequest(Long courseId, Long requestId) {
        Course course = loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);

        if (!enrollmentRepository.existsByStudentAndCourse(request.getStudent(), course)) {
            enrollmentRepository.save(
                    Enrollment.builder()
                            .student(request.getStudent())
                            .course(course)
                            .build()
            );
        }
        request.approve();
        notifyJoinRequestProcessed(request, NotificationType.COURSE_JOIN_APPROVED, "강의실 가입 승인",
                "%s 강의실 가입이 승인되었습니다.".formatted(course.getTitle()));
    }

    @Transactional
    public void rejectJoinRequest(Long courseId, Long requestId) {
        Course course = loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        request.reject();
        notifyJoinRequestProcessed(request, NotificationType.COURSE_JOIN_REJECTED, "강의실 가입 거절",
                "%s 강의실 가입이 거절되었습니다.".formatted(course.getTitle()));
    }

    @Transactional
    public void blockJoinRequest(Long courseId, Long requestId) {
        Course course = loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        request.block();
        notifyJoinRequestProcessed(request, NotificationType.COURSE_JOIN_BLOCKED, "강의실 가입 차단",
                "%s 강의실에서 차단되었습니다.".formatted(course.getTitle()));
    }

    private void notifyJoinRequestProcessed(CourseJoinRequest request,
                                            NotificationType type,
                                            String title,
                                            String body) {
        User recipient = request.getStudent().getUser();
        Long courseId = request.getCourse().getId();
        notificationService.notify(recipient, type, title, body, "course", courseId);
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

    private CourseJoinRequest loadPendingRequest(Long courseId, Long requestId) {
        CourseJoinRequest request = joinRequestRepository.findByIdAndCourseId(requestId, courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.JOIN_REQUEST_NOT_FOUND));
        if (request.getStatus() != CourseJoinRequestStatus.PENDING) {
            throw new BusinessException(CommonErrorCode.JOIN_REQUEST_ALREADY_PROCESSED);
        }
        return request;
    }
}
