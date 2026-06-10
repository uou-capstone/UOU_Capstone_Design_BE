package io.github.uou_capstone.aiplatform.domain.course.repository;

import io.github.uou_capstone.aiplatform.config.JpaAuditingConfig;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
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
class CourseRepositoryDetailFetchTest {

    @Autowired
    private CourseRepository courseRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    void findDetailByIdFetchesTeacherUserAndLecturesForDetachedDtoMapping() {
        User teacherUser = User.builder()
                .email("teacher-detail@example.com")
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
                .invitationCode("detail-invite-code")
                .build();
        em.persist(course);

        Lecture lecture = Lecture.builder()
                .course(course)
                .title("week 1")
                .weekNumber(1)
                .description("lecture")
                .build();
        em.persist(lecture);
        em.flush();
        Long courseId = course.getId();

        em.clear();

        Course fetched = courseRepository.findDetailById(courseId).orElseThrow();

        em.clear();

        assertThatCode(() -> new CourseResponseDto(fetched)).doesNotThrowAnyException();

        CourseResponseDto response = new CourseResponseDto(fetched);
        assertThat(response.getTeacherName()).isEqualTo("teacher");
        assertThat(response.getLectures()).hasSize(1);
    }
}
