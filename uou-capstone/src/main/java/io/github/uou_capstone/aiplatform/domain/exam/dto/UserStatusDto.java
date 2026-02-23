package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 사용자 수준
 */
@Getter
@Setter
public class UserStatusDto {
    private String proficiencyLevel; // "beginner", "intermediate", "advanced"
    private List<String> weaknessFocus; // 약점 집중 영역
}
