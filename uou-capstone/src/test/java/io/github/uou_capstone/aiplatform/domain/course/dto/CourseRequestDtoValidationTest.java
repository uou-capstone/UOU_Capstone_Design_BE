package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CourseRequestDtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void update_blankTitle_fails() {
        CourseUpdateRequestDto dto = build(new CourseUpdateRequestDto(), "  ", "valid description");
        Set<ConstraintViolation<CourseUpdateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("title"));
    }

    @Test
    void update_titleOver255_fails() {
        String longTitle = "가".repeat(256);
        CourseUpdateRequestDto dto = build(new CourseUpdateRequestDto(), longTitle, "ok");
        Set<ConstraintViolation<CourseUpdateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("title")
                        && v.getMessage().contains("255자"));
    }

    @Test
    void update_descriptionOver20000_fails() {
        String longDesc = "a".repeat(20001);
        CourseUpdateRequestDto dto = build(new CourseUpdateRequestDto(), "ok", longDesc);
        Set<ConstraintViolation<CourseUpdateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v ->
                v.getPropertyPath().toString().equals("description")
                        && v.getMessage().contains("20000자"));
    }

    @Test
    void update_unicodeDescriptionUnder20000_passes() {
        // 한글 + 이모지 + 줄바꿈 + 특수문자 포함, 20000자 이하
        StringBuilder sb = new StringBuilder();
        sb.append("강의 설명 본문\n").append("줄바꿈도 포함됨\r\n");
        sb.append("이모지 🚀✨🎉 와 특수문자 <>{}[]&*!@#$%^()\n");
        while (sb.length() < 19000) {
            sb.append("한글 본문 line\n");
        }
        String desc = sb.substring(0, Math.min(sb.length(), 20000));

        CourseUpdateRequestDto dto = build(new CourseUpdateRequestDto(), "정상 제목", desc);
        Set<ConstraintViolation<CourseUpdateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void create_titleOver255_fails() {
        CourseCreateRequestDto dto = build(new CourseCreateRequestDto(), "x".repeat(256), "ok");
        Set<ConstraintViolation<CourseCreateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("title"));
    }

    @Test
    void create_descriptionOver20000_fails() {
        CourseCreateRequestDto dto = build(new CourseCreateRequestDto(), "ok", "x".repeat(20001));
        Set<ConstraintViolation<CourseCreateRequestDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("description"));
    }

    /** Lombok @Getter만 있는 DTO에 값을 주입하기 위해 리플렉션을 사용한다. */
    private static <T> T build(T target, String title, String description) {
        try {
            Field titleField = target.getClass().getDeclaredField("title");
            titleField.setAccessible(true);
            titleField.set(target, title);

            Field descField = target.getClass().getDeclaredField("description");
            descField.setAccessible(true);
            descField.set(target, description);
            return target;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
