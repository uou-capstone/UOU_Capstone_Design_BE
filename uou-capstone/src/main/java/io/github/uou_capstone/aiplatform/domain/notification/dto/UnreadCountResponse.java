package io.github.uou_capstone.aiplatform.domain.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class UnreadCountResponse {
    private final long count;
}
