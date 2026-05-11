package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 상호작용 도메인(notice / discussion / attendance) 공통 권한·로딩 헬퍼.
 *
 * <p>주의: {@code currentUserResolver.getTeacher()} 를 직접 호출하면 학생 사용자에서 {@code MEMBER_NOT_FOUND}
 * 예외가 떨어진다. 권한 의미상 {@code FORBIDDEN} 이 맞으므로 이 서비스는 항상 {@code getUser()} 를 받아
 * role 부재 시 {@code FORBIDDEN} 으로 던진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseAccessService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 본인이 강의실 교사임을 보장. 학생 사용자거나 타인 강의실이면 FORBIDDEN.
     * COURSE_NOT_FOUND 는 강의실이 아예 없을 때만.
     */
    public Course loadCourseAsTeacher(Long courseId) {
        User user = currentUserResolver.getUser();
        Teacher teacher = user.getTeacher();
        if (teacher == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        if (!course.getTeacher().getId().equals(teacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return course;
    }

    /**
     * 강의실 교사 OR ACTIVE 수강생임을 보장. 둘 다 아니면 FORBIDDEN.
     * 학생은 EnrollmentStatus.ACTIVE 만 허용 — COMPLETED/DROPPED 는 차단.
     */
    public Course loadCourseAsParticipant(Long courseId) {
        User user = currentUserResolver.getUser();
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        Teacher teacher = user.getTeacher();
        if (teacher != null && course.getTeacher().getId().equals(teacher.getId())) {
            return course;
        }
        Student student = user.getStudent();
        if (student != null && enrollmentRepository.existsByStudentAndCourseAndStatus(
                student, course, EnrollmentStatus.ACTIVE)) {
            return course;
        }
        throw new BusinessException(CommonErrorCode.FORBIDDEN);
    }

    /** 본인이 강의실 교사인지 (예외 없이 boolean). 작성자 확인용 분기에 사용. */
    public boolean isCourseTeacher(Course course, User user) {
        Teacher teacher = user.getTeacher();
        return teacher != null && course.getTeacher().getId().equals(teacher.getId());
    }

    /**
     * 게시글·댓글 작성자 보장 (PATCH 용). 아니면 FORBIDDEN.
     * 비교는 User.id 기준 — Notice 처럼 author 가 Teacher 엔티티인 경우 호출부에서
     * {@code notice.getAuthor().getUser().getId()} 를 넘길 것.
     */
    public void ensureAuthor(User currentUser, Long authorUserId) {
        if (!currentUser.getId().equals(authorUserId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /**
     * 작성자 또는 강의실 교사 보장 (DELETE 용). 아니면 FORBIDDEN.
     */
    public void ensureAuthorOrCourseTeacher(User currentUser, Course course, Long authorUserId) {
        if (currentUser.getId().equals(authorUserId)) return;
        if (isCourseTeacher(course, currentUser)) return;
        throw new BusinessException(CommonErrorCode.FORBIDDEN);
    }
}
