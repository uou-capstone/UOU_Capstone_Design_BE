package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(ExamSessionRepositoryMaterialReferenceTest.NoMetricsConfig.class)
class ExamSessionRepositoryMaterialReferenceTest {

    @Autowired ExamSessionRepository examSessionRepository;

    @PersistenceContext
    EntityManager em;

    @Test
    void clearMaterialReference_allowsDeletingReferencedMaterial() {
        User teacherUser = User.builder()
                .email("material-ref-teacher@example.com")
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
                .invitationCode("material-ref-course")
                .build();
        em.persist(course);

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("description")
                .build();
        em.persist(lecture);

        Material material = Material.builder()
                .lecture(lecture)
                .displayName("material.pdf")
                .materialType("PDF")
                .filePath("uploads/material.pdf")
                .uploadedBy(teacherUser.getId())
                .build();
        em.persist(material);

        ExamSession session = ExamSession.builder()
                .lecture(lecture)
                .material(material)
                .displayName("quiz")
                .user(teacherUser)
                .examType(ExamType.FIVE_CHOICE)
                .targetCount(5)
                .build();
        em.persist(session);
        em.flush();

        Long materialId = material.getId();
        Long sessionId = session.getId();
        em.clear();

        int updated = examSessionRepository.clearMaterialReference(materialId);
        em.flush();
        em.clear();

        ExamSession updatedSession = em.find(ExamSession.class, sessionId);
        assertThat(updated).isEqualTo(1);
        assertThat(updatedSession.getMaterial()).isNull();

        Material savedMaterial = em.find(Material.class, materialId);
        em.remove(savedMaterial);
        em.flush();
        em.clear();

        assertThat(em.find(Material.class, materialId)).isNull();
        assertThat(em.find(ExamSession.class, sessionId)).isNotNull();
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class NoMetricsConfig {
    }
}
