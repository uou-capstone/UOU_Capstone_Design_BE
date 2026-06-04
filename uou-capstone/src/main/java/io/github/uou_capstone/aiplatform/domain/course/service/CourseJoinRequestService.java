package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestListItemDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.JoinRequestBulkRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.JoinRequestBulkResultDto;
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
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;
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

import java.util.ArrayList;
import java.util.List;
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
    private final TeacherNotificationPublisher teacherNotificationPublisher;
    private final DistributedLockService distributedLockService;
    private final TransactionTemplate transactionTemplate;
    private final CourseAuditLogger auditLogger;

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

        // 과거에 APPROVED 또는 REJECTED 이력이 있는 학생이 다시 들어오는 경우 — 재가입 감사 로그.
        boolean hadHistory = joinRequestRepository.existsByStudentIdAndCourseIdAndStatusIn(
                student.getId(), course.getId(),
                Set.of(CourseJoinRequestStatus.APPROVED, CourseJoinRequestStatus.REJECTED));
        if (hadHistory) {
            auditLogger.studentRejoined(course.getId(), student.getId());
        }

        CourseJoinRequest saved = joinRequestRepository.save(
                CourseJoinRequest.builder()
                        .student(student)
                        .course(course)
                        .build()
        );

        // 담당 교사에게 가입 요청 알림 (학생이 actor 이므로 자기 작업 분기는 발생하지 않음)
        teacherNotificationPublisher.notifyCourseTeacher(
                course,
                student.getUser(),
                NotificationType.COURSE_JOIN_REQUESTED,
                "새 강의실 가입 요청",
                "%s 학생이 %s 강의실 가입을 요청했습니다."
                        .formatted(student.getUser().getFullName(), course.getTitle()),
                "course",
                course.getId()
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
        approveInternal(course, request);
    }

    @Transactional
    public void rejectJoinRequest(Long courseId, Long requestId) {
        Course course = loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        rejectInternal(course, request);
    }

    @Transactional
    public void blockJoinRequest(Long courseId, Long requestId) {
        Course course = loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        request.block();
        notifyJoinRequestProcessed(request, NotificationType.COURSE_JOIN_BLOCKED, "강의실 가입 차단",
                "%s 강의실에서 차단되었습니다.".formatted(course.getTitle()));
        auditLogger.joinBlocked(course.getId(), request.getStudent().getId(),
                currentUserResolver.getUser().getId());
    }

    /**
     * 가입 요청 일괄 승인. 각 요청은 개별 트랜잭션으로 처리되어 한 건 실패가 다른 건을 막지 않는다.
     * 학기 초 대량 승인 처리에 사용한다.
     */
    public JoinRequestBulkResultDto approveJoinRequestsBulk(Long courseId, JoinRequestBulkRequestDto dto) {
        return processBulk(courseId, dto, this::approveInternal);
    }

    /**
     * 가입 요청 일괄 거절. 각 요청은 개별 트랜잭션으로 처리된다.
     */
    public JoinRequestBulkResultDto rejectJoinRequestsBulk(Long courseId, JoinRequestBulkRequestDto dto) {
        return processBulk(courseId, dto, this::rejectInternal);
    }

    private JoinRequestBulkResultDto processBulk(Long courseId,
                                                 JoinRequestBulkRequestDto dto,
                                                 BulkAction action) {
        // 권한 체크는 매 요청마다 다시 일어나지만, 한 번 미리 검증해 잘못된 courseId 일 때 빠르게 끊는다.
        loadOwnedCourse(courseId);

        List<JoinRequestBulkResultDto.Item> items = new ArrayList<>(dto.getRequestIds().size());
        for (Long requestId : dto.getRequestIds()) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Course course = loadOwnedCourse(courseId);
                    CourseJoinRequest request = loadPendingRequest(courseId, requestId);
                    action.run(course, request);
                });
                items.add(JoinRequestBulkResultDto.Item.success(requestId));
            } catch (BusinessException ex) {
                items.add(JoinRequestBulkResultDto.Item.failure(requestId, ex.getErrorCode().getCode()));
            }
        }
        return new JoinRequestBulkResultDto(items);
    }

    @FunctionalInterface
    private interface BulkAction {
        void run(Course course, CourseJoinRequest request);
    }

    private void approveInternal(Course course, CourseJoinRequest request) {
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
        auditLogger.joinApproved(course.getId(), request.getStudent().getId(),
                currentUserResolver.getUser().getId());
    }

    private void rejectInternal(Course course, CourseJoinRequest request) {
        request.reject();
        notifyJoinRequestProcessed(request, NotificationType.COURSE_JOIN_REJECTED, "강의실 가입 거절",
                "%s 강의실 가입이 거절되었습니다.".formatted(course.getTitle()));
        auditLogger.joinRejected(course.getId(), request.getStudent().getId(),
                currentUserResolver.getUser().getId());
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
