package io.github.uou_capstone.aiplatform.domain.exam.studio.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Exam Studio PDF Context 발급 요청.
 *
 * <p>현재 Spring은 {@code materialId} 만 받는다. {@code pdfPath} / {@code pdfText}는 서비스 레이어에서
 * Material 엔티티를 통해 결정한다.
 */
@Getter
@Setter
@NoArgsConstructor
public class ExamStudioPdfContextRequest {

    @NotNull
    private Long materialId;
}
