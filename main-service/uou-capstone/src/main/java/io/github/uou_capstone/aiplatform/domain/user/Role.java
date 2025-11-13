package io.github.uou_capstone.aiplatform.domain.user;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    STUDENT("학생"),
    TEACHER("선생님");

    private final String value;
}