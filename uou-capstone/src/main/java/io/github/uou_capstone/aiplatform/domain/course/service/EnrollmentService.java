package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final EnrollmentRepository enrollmentRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;

    @Transactional
    public Long enrollCourse(Long courseId) {
        // 1. 현재 로그인한 학생 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 수강 신청할 강의실 정보 가져오기
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        // 3. 이미 수강 신청했는지 확인 (중복 방지)
        if (enrollmentRepository.existsByStudentAndCourse(student, course)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE);
        }

        // 4. Enrollment 생성 및 저장
        Enrollment enrollment = Enrollment.builder()
                .student(student)
                .course(course)
                .build();
        enrollmentRepository.save(enrollment);

        return enrollment.getId();
    }

    @Transactional
    public Long enrollCourseByCode(String invitationCode) {
        // 1. 현재 로그인한 학생 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 인증코드로 강의실 정보 가져오기
        Course course = courseRepository.findByInvitationCode(invitationCode)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INVITATION_CODE));

        // 3. 이미 수강 신청했는지 확인 (중복 방지)
        if (enrollmentRepository.existsByStudentAndCourse(student, course)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE);
        }

        // 4. Enrollment 생성 및 저장
        Enrollment enrollment = Enrollment.builder()
                .student(student)
                .course(course)
                .build();
        enrollmentRepository.save(enrollment);

        return enrollment.getId();
    }
}
