package io.github.uou_capstone.aiplatform.domain.course.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CourseStudentItemDtoTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void serializesEnrolledAtAsOfficialIsoOffsetDateTimeField() throws Exception {
        CourseStudentItemDto dto = new CourseStudentItemDto(enrollmentWithCreatedAt(
                LocalDateTime.of(2026, 5, 22, 12, 34, 56)));

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"enrolledAt\":\"2026-05-22T12:34:56Z\"");
        assertThat(json).doesNotContain("createdAt");
        assertThat(json).doesNotContain("enrolled_at");
    }

    private Enrollment enrollmentWithCreatedAt(LocalDateTime createdAt) {
        User teacherUser = User.builder()
                .email("teacher@example.com")
                .password("p")
                .fullName("teacher")
                .role(Role.TEACHER)
                .build();
        Teacher teacher = Teacher.builder()
                .schoolName("school")
                .department("department")
                .user(teacherUser)
                .build();
        Course course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("description")
                .invitationCode("invite")
                .build();

        User studentUser = User.builder()
                .email("student@example.com")
                .password("p")
                .fullName("student")
                .role(Role.STUDENT)
                .build();
        Student student = Student.builder()
                .user(studentUser)
                .grade(1)
                .classNumber("1-1")
                .build();
        ReflectionTestUtils.setField(student, "id", 20L);

        Enrollment enrollment = Enrollment.builder()
                .student(student)
                .course(course)
                .build();
        ReflectionTestUtils.setField(enrollment, "id", 10L);
        ReflectionTestUtils.setField(enrollment, "createdAt", createdAt);
        return enrollment;
    }
}
