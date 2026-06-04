package io.github.uou_capstone.aiplatform.domain.material.repository;

import io.github.uou_capstone.aiplatform.config.JpaAuditingConfig;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
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
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
class MaterialRepositoryFileFetchTest {

    @Autowired
    private MaterialRepository materialRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void findByIdWithLectureAndCourseFetchesGraphForDetachedAuthorization() {
        User teacherUser = User.builder()
                .email("material-teacher@example.com")
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
                .invitationCode("material-fetch-code")
                .build();
        em.persist(course);

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("week 1")
                .weekNumber(1)
                .description("lecture")
                .build();
        em.persist(lecture);

        Material material = Material.builder()
                .lecture(lecture)
                .displayName("lecture.pdf")
                .materialType("PDF")
                .filePath("uploads/lecture.pdf")
                .uploadedBy(teacherUser.getId())
                .build();
        em.persist(material);
        em.flush();
        Long materialId = material.getId();

        em.clear();

        Material fetched = materialRepository.findByIdWithLectureAndCourse(materialId).orElseThrow();

        em.clear();

        assertThatCode(() -> fetched.getLecture().getCourse().getTeacher().getId())
                .doesNotThrowAnyException();
        assertThat(fetched.getFilePath()).isEqualTo("uploads/lecture.pdf");
    }
}
