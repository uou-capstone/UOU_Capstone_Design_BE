package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

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

    private LocalTime startTime;
    private LocalTime endTime;

    /** lecture 매핑 회차일 때만 채움. 같은 강의실 소속이어야 함 (서비스 검증). */
    private Long lectureId;
}
