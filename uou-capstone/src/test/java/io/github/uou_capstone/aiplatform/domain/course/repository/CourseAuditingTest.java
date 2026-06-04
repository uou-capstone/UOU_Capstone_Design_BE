package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.config.JpaAuditingConfig;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class CourseAuditingTest {

    @PersistenceContext
    EntityManager em;

    @Test
    void saveCourseSetsAuditTimestamps() {
        User teacherUser = User.builder()
                .email("teacher-audit@example.com")
                .password("password")
                .fullName("teacher")
                .role(Role.TEACHER)
                .build();
        em.persist(teacherUser);

        Teacher teacher = Teacher.builder()
                .schoolName("school")
                .department("department")
                .user(teacherUser)
                .build();
        em.persist(teacher);

        Course course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("description")
                .invitationCode("audit-invite-code")
                .build();
        em.persist(course);
        em.flush();
        Long courseId = course.getId();

        em.clear();

        Course saved = em.find(Course.class, courseId);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
