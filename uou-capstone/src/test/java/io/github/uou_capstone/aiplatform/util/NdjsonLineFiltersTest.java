package io.github.uou_capstone.aiplatform.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NdjsonLineFiltersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void detectsHeartbeat() {
        assertThat(NdjsonLineFilters.isHeartbeatLine(mapper, "{\"type\":\"heartbeat\"}")).isTrue();
        assertThat(NdjsonLineFilters.isHeartbeatLine(mapper, "{\"type\": \"heartbeat\"}")).isTrue();
    }

    @Test
    void nonHeartbeatOrInvalidNotMarkedHeartbeat() {
        assertThat(NdjsonLineFilters.isHeartbeatLine(mapper, "{\"type\":\"agent_delta\"}")).isFalse();
        assertThat(NdjsonLineFilters.isHeartbeatLine(mapper, "not json")).isFalse();
        assertThat(NdjsonLineFilters.isHeartbeatLine(mapper, "")).isFalse();
    }
}
