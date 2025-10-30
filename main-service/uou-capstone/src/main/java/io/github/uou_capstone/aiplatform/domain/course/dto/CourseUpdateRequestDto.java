package io.github.uou_capstone.aiplatform.domain.course.dto;

import lombok.Getter;

@Getter
public class CourseUpdateRequestDto { // 제목이나 설명 중 하나만 수정할 수도 있으므로, 필수는 아님
    private String title;       // 수정할 제목 (선택적)
    private String description; // 수정할 설명 (선택적)
}