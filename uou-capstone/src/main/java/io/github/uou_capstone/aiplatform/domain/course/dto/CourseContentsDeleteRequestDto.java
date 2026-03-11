package io.github.uou_capstone.aiplatform.domain.course.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 강의실 내 주차별 강의자료·시험·생성세션 일괄 삭제 요청
 * (GET /api/courses/{courseId}/contents 조회 결과와 동일한 스코프에서 삭제할 ID 목록)
 */
@Getter
@Setter
@NoArgsConstructor
public class CourseContentsDeleteRequestDto {

    /** 삭제할 강의자료(Material) ID 목록 */
    @NotNull(message = "materialIds는 null일 수 없습니다. 비울 경우 빈 배열을 보내세요.")
    private List<Long> materialIds = new ArrayList<>();

    /** 삭제할 시험 세션(ExamSession) ID 목록 */
    @NotNull(message = "examSessionIds는 null일 수 없습니다. 비울 경우 빈 배열을 보내세요.")
    private List<Long> examSessionIds = new ArrayList<>();

    /** 삭제할 자료 생성 세션(GenerationSession) ID 목록 */
    @NotNull(message = "generationSessionIds는 null일 수 없습니다. 비울 경우 빈 배열을 보내세요.")
    private List<Long> generationSessionIds = new ArrayList<>();
}
