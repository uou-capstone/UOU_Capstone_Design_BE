package io.github.uou_capstone.aiplatform.domain.course.report.classroom.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.entity.ClassroomReport;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code classroom_reports.course_id} UNIQUE 제약이 실제 DB(H2 MODE=MySQL) 에서 동작하는지 검증.
 *
 * <p>{@link io.github.uou_capstone.aiplatform.domain.course.report.classroom.service.ClassroomReportPersister}
 * 의 race recovery 가 의존하는 마지막 방어선이다. 이 제약이 동작하지 않으면 동시 insert race 시
 * 두 row 가 만들어져 UPSERT 의미가 깨진다.
 *
 * <p>단일 스레드 시뮬레이션 — 같은 course 에 대해 두 번째 entity 를 persist 하면
 * {@link DataIntegrityViolationException} 이 발생해야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(ClassroomReportUniqueConstraintTest.NoMetricsConfig.class)
class ClassroomReportUniqueConstraintTest {

    @Autowired ClassroomReportRepository classroomReportRepository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("같은 course 에 ClassroomReport 두 번째 insert → DataIntegrityViolationException")
    void duplicateInsertRejectedByUniqueConstraint() {
        Course course = setupCourse("uniq-clr-1");

        ClassroomReport first = ClassroomReport.builder()
                .course(course)
                .summaryMarkdown("first")
                .fallbackUsed(false)
                .build();
        classroomReportRepository.saveAndFlush(first);

        em.clear(); // detach — 두 번째 entity 가 신규 persist 로 처리되도록

        ClassroomReport second = ClassroomReport.builder()
                .course(course)
                .summaryMarkdown("second")
                .fallbackUsed(false)
                .build();

        assertThatThrownBy(() -> classroomReportRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("서로 다른 course 에 각 1개씩 insert — 둘 다 성공")
    void differentCoursesAllowed() {
        Course courseA = setupCourse("uniq-clr-a");
        Course courseB = setupCourse("uniq-clr-b");

        classroomReportRepository.saveAndFlush(ClassroomReport.builder()
                .course(courseA).summaryMarkdown("a").fallbackUsed(false).build());
        classroomReportRepository.saveAndFlush(ClassroomReport.builder()
                .course(courseB).summaryMarkdown("b").fallbackUsed(false).build());

        assertThat(classroomReportRepository.findAll()).hasSize(2);
    }

    private Course setupCourse(String invitationCode) {
        User teacherUser = User.builder()
                .email(invitationCode + "@t.com").password("p").fullName("teacher")
                .role(Role.TEACHER).build();
        em.persist(teacherUser);

        Teacher teacher = Teacher.builder()
                .schoolName("s").department("d").user(teacherUser).build();
        em.persist(teacher);

        Course course = Course.builder()
                .teacher(teacher).title("c").description("d").invitationCode(invitationCode).build();
        em.persist(course);
        em.flush();
        return course;
    }

    /** Spring Boot Actuator 빈을 @DataJpaTest 슬라이스에서 끔. */
    @org.springframework.boot.test.context.TestConfiguration
    static class NoMetricsConfig {
    }
}
