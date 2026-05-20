package io.github.uou_capstone.aiplatform.domain.course.dto;

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
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 20, 10, 30, 15);
        ReflectionTestUtils.setField(course, "createdAt", createdAt);

        CourseResponseDto response = new CourseResponseDto(course);

        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
    }
}
