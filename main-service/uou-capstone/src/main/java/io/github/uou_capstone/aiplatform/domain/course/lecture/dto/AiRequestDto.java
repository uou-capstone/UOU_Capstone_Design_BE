package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;


/**
 * (Spring → FastAPI 로 보낼 요청)
 * // 예시: AI가 처리할 PDF 파일의 경로
 * (실제로는 파일 자체를 보낼 수도 있습니다)
 */
@Getter
@AllArgsConstructor
public class AiRequestDto {
    private String pdfFilePath;

}