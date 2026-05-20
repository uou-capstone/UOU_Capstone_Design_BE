package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttendanceSessionTimeFormatTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void createRequest_accepts_string_time_contract() throws Exception {
        String json = """
                {
                  "title": "attendance-1",
                  "sessionDate": "2026-05-20",
                  "startTime": "10:00:00",
                  "endTime": "12:00:00"
                }
                """;

        AttendanceSessionCreateRequestDto dto =
                mapper.readValue(json, AttendanceSessionCreateRequestDto.class);

        assertThat(dto.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(dto.getEndTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void createRequest_serializes_time_as_hh_mm_ss_string() throws Exception {
        String json = """
                {
                  "title": "attendance-1",
                  "sessionDate": "2026-05-20",
                  "startTime": "10:00:00",
                  "endTime": "12:00:00"
                }
                """;

        AttendanceSessionCreateRequestDto dto =
                mapper.readValue(json, AttendanceSessionCreateRequestDto.class);

        String serialized = mapper.writeValueAsString(dto);

        assertThat(serialized).contains("\"startTime\":\"10:00:00\"");
        assertThat(serialized).contains("\"endTime\":\"12:00:00\"");
    }

    @Test
    void createRequest_rejects_swagger_generated_object_time_shape() {
        String json = """
                {
                  "title": "attendance-1",
                  "sessionDate": "2026-05-20",
                  "startTime": { "hour": 10, "minute": 0, "second": 0, "nano": 0 },
                  "endTime": { "hour": 12, "minute": 0, "second": 0, "nano": 0 }
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(json, AttendanceSessionCreateRequestDto.class))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("startTime");
    }
}
