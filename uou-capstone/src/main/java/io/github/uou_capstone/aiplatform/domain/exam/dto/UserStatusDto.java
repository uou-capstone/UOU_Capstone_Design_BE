package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 사용자 수준 (FastAPI ko 스키마 UserStatus와 동일)
 */
@Getter
@Setter
public class UserStatusDto {
    private String proficiencyLevel; // "Beginner", "Intermediate", "Advanced"
    /** true면 오답 노트/취약점 기반 문제 생성. FastAPI(ko)는 Optional[bool] */
    private Boolean weaknessFocus;
}
