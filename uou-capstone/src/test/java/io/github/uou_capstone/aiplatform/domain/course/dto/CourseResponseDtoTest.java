package io.github.uou_capstone.aiplatform.domain.course.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CourseResponseDtoTest {

    @Test
    void mapsCreatedAtFromCourse() {
        Course course = courseWithCreatedAt(LocalDateTime.of(2026, 5, 20, 10, 30, 15));

        CourseResponseDto response = new CourseResponseDto(course);

        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 20, 10, 30, 15));
    }

    @Test
    void serializesCreatedAtAsCamelCase() throws Exception {
        Course course = courseWithCreatedAt(LocalDateTime.of(2026, 5, 20, 10, 30, 15));
        CourseResponseDto response = new CourseResponseDto(course);
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"createdAt\":\"2026-05-20T10:30:15\"");
        assertThat(json).doesNotContain("created_at");
    }

    private Course courseWithCreatedAt(LocalDateTime createdAt) {
        User teacherUser = User.builder()
                .email("teacher@example.com")
                .password("password")
                .fullName("teacher")
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
                .invitationCode("invite-code")
                .build();
        ReflectionTestUtils.setField(course, "createdAt", createdAt);
        return course;
    }
}
