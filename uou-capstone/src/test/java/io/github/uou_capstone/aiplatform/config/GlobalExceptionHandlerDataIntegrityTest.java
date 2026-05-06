package io.github.uou_capstone.aiplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerDataIntegrityTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new ObjectMapper());
    }

    @Test
    void mysqlDataTooLong_mapsTo400WithLengthMessage() {
        // MySQL 컬럼 길이 초과 시 실제 root cause 형태를 모사
        SQLException sqlEx = new SQLException("Data truncation: Data too long for column 'description' at row 1");
        DataException hibernateDataEx = new DataException("could not execute statement", sqlEx);
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", hibernateDataEx);

        HttpServletRequest req = new MockHttpServletRequest("PUT", "/api/courses/123");

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getError()).isEqualTo("BAD_REQUEST");
        assertThat(body.getCode()).isEqualTo("4000");
        assertThat(body.getMessage()).contains("길이");
        assertThat(body.getPath()).isEqualTo("/api/courses/123");
    }

    @Test
    void otherIntegrityViolation_mapsTo400WithGenericMessage() {
        // unique 제약 위반 등은 길이 초과 메시지가 아니어야 함
        SQLException sqlEx = new SQLException("Duplicate entry 'foo' for key 'courses.invitation_code'");
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement", sqlEx);

        HttpServletRequest req = new MockHttpServletRequest("POST", "/api/courses");

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(ex, req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo("4000");
        assertThat(body.getMessage()).doesNotContain("길이");
        assertThat(body.getMessage()).contains("제약");
    }
}
