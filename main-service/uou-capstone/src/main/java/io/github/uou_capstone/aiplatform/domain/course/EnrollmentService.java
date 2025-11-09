package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.user.Student;
import io.github.uou_capstone.aiplatform.domain.user.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
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
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("학생 정보를 찾을 수 없습니다."));

        // 2. 수강 신청할 과목 정보 가져오기
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("과목 정보를 찾을 수 없습니다."));

        // 3. 이미 수강 신청했는지 확인 (중복 방지)
        if (enrollmentRepository.existsByStudentAndCourse(student, course)) {
            throw new IllegalStateException("이미 수강 신청한 과목입니다.");
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