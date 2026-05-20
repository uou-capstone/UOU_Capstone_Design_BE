package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@NoArgsConstructor
public class AttendanceSessionCreateRequestDto {

    @NotBlank
    @Size(max = 100)
    private String title;

    @NotNull
    private LocalDate sessionDate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    @Schema(type = "string", format = "time", example = "10:00:00",
            description = "출석 회차 시작 시간 (HH:mm:ss)")
    private LocalTime startTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    @Schema(type = "string", format = "time", example = "12:00:00",
            description = "출석 회차 종료 시간 (HH:mm:ss)")
    private LocalTime endTime;

    /** lecture 매핑 회차일 때만 채움. 같은 강의실 소속이어야 함 (서비스 검증). */
    private Long lectureId;
}
