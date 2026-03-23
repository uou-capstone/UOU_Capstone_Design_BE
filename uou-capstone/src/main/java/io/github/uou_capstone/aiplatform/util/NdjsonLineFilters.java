package io.github.uou_capstone.aiplatform.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FastAPI NDJSON 스트림 라인 필터 (v2/v3 공통 계약).
 */
public final class NdjsonLineFilters {

    private NdjsonLineFilters() {
    }

    /**
     * {@code {"type":"heartbeat"}} 형태의 라인이면 true.
     * 파싱 실패 시 false (해당 라인은 그대로 통과시키기 위해).
     */
    public static boolean isHeartbeatLine(ObjectMapper objectMapper, String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        try {
            return "heartbeat".equals(objectMapper.readTree(line).path("type").asText());
        } catch (Exception e) {
            return false;
        }
    }
}
