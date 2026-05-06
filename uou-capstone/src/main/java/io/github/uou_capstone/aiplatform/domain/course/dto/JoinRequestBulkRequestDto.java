package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class JoinRequestBulkRequestDto {

    /** 한 번에 처리 가능한 요청 ID 개수 상한. 메모리/트랜잭션 폭주 방지. */
    public static final int MAX_BATCH_SIZE = 100;

    @NotEmpty(message = "requestIds 는 비어 있을 수 없습니다.")
    @Size(max = MAX_BATCH_SIZE, message = "한 번에 최대 100건까지 처리할 수 있습니다.")
    private List<Long> requestIds;

    public JoinRequestBulkRequestDto(List<Long> requestIds) {
        this.requestIds = requestIds;
    }
}
