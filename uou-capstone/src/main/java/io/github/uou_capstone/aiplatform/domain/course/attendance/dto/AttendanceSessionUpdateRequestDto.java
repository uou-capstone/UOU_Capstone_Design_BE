package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * PATCH 부분 갱신. lectureId 는 null/명시적 변경 모두 허용을 위해 wrapper 사용.
 * lectureId 를 명시적으로 null 로 지우고 싶을 때 클라이언트가 null 을 보내도록 한다.
 * (현재 구현은 lectureId 미전송 시에도 null 로 처리되므로 주의 — 호출부에서 lectureChanged 플래그로 분기하려면
 * 별도 필드 추가 필요. 1차 단순화: lectureId 가 들어오면 항상 적용.)
 */
@Getter
@NoArgsConstructor
public class AttendanceSessionUpdateRequestDto {

    @Size(max = 100)
    private String title;

    private LocalDate sessionDate;
    private LocalTime startTime;
    private LocalTime endTime;

    private Long lectureId;
}
