package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(StudentReportAnalysisUniqueConstraintTest.NoMetricsConfig.class)
class StudentReportAnalysisUniqueConstraintTest {

    @Autowired StudentReportAnalysisRepository repository;

    @PersistenceContext
    EntityManager em;

    @Test
    void duplicateCourseStudentInsertRejectedByUniqueConstraint() {
        Course course = setupCourse("uniq-sra-1");
        Student student = setupStudent("uniq-sra-1");

        repository.saveAndFlush(StudentReportAnalysis.builder()
                .course(course)
                .student(student)
                .analysisJson("{}")
                .fallbackUsed(false)
                .build());

        em.clear();

        assertThatThrownBy(() -> repository.saveAndFlush(StudentReportAnalysis.builder()
                .course(course)
                .student(student)
                .analysisJson("{\"second\":true}")
                .fallbackUsed(false)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameStudentDifferentCoursesAllowed() {
        Course courseA = setupCourse("uniq-sra-a");
        Course courseB = setupCourse("uniq-sra-b");
        Student student = setupStudent("uniq-sra-a");

        repository.saveAndFlush(StudentReportAnalysis.builder()
                .course(courseA).student(student).analysisJson("{}").fallbackUsed(false).build());
        repository.saveAndFlush(StudentReportAnalysis.builder()
                .course(courseB).student(student).analysisJson("{}").fallbackUsed(false).build());

        assertThat(repository.findAll()).hasSize(2);
    }

    private Course setupCourse(String invitationCode) {
        User teacherUser = User.builder()
                .email(invitationCode + "@t.com")
                .password("p")
                .fullName("teacher")
                .role(Role.TEACHER)
                .build();
        em.persist(teacherUser);

        Teacher teacher = Teacher.builder()
                .schoolName("s")
                .department("d")
                .user(teacherUser)
                .build();
        em.persist(teacher);

        Course course = Course.builder()
                .teacher(teacher)
                .title("c")
                .description("d")
                .invitationCode(invitationCode)
                .build();
        em.persist(course);
        em.flush();
        return course;
    }

    private Student setupStudent(String emailPrefix) {
        User studentUser = User.builder()
                .email(emailPrefix + "@s.com")
                .password("p")
                .fullName("student")
                .role(Role.STUDENT)
                .build();
        em.persist(studentUser);

        Student student = Student.builder()
                .user(studentUser)
                .grade(1)
                .classNumber("1")
                .build();
        em.persist(student);
        em.flush();
        return student;
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class NoMetricsConfig {
    }
}
