package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestListItemDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequest;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseJoinRequestRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
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
public class CourseJoinRequestService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final CourseJoinRequestRepository joinRequestRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional
    public CourseJoinRequestResponseDto createJoinRequest(CourseJoinRequestCreateDto dto) {
        Student student = currentUserResolver.getStudent();

        Course course = courseRepository.findByInvitationCode(dto.getInvitationCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INVITATION_CODE));

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
    }

    @Transactional
    public void rejectJoinRequest(Long courseId, Long requestId) {
        loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        request.reject();
    }

    @Transactional
    public void blockJoinRequest(Long courseId, Long requestId) {
        loadOwnedCourse(courseId);
        CourseJoinRequest request = loadPendingRequest(courseId, requestId);
        request.block();
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
