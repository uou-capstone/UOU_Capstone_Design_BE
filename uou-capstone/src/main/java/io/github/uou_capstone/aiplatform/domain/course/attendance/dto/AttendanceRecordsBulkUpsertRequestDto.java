package io.github.uou_capstone.aiplatform.domain.course.attendance.dto;

import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class AttendanceRecordsBulkUpsertRequestDto {

    @NotEmpty(message = "items 는 비어있을 수 없습니다.")
    @Valid
    private List<Item> items;

    @Getter
    @NoArgsConstructor
    public static class Item {
        @NotNull
        private Long studentId;

        @NotNull
        private AttendanceStatus status;

        @Size(max = 255)
        private String note;
    }
}
