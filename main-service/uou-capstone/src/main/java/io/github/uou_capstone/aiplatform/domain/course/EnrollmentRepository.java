package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.user.Student;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    // 학생과 과목으로 수강 등록 정보가 있는지 확인
    boolean existsByStudentAndCourse(Student student, Course course);
}
