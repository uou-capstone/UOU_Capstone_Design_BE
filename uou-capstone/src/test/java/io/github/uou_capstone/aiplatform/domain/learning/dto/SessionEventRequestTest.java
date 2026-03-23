package io.github.uou_capstone.aiplatform.domain.learning.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionEventRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesTypeAndExtraFieldsIntoPayload() throws Exception {
        String json = """
                {
                  "type": "USER_MESSAGE",
                  "text": "hello",
                  "page": 3
                }
                """;

        SessionEventRequest req = mapper.readValue(json, SessionEventRequest.class);

        assertThat(req.getType()).isEqualTo("USER_MESSAGE");
        Map<String, Object> payload = req.toPayload();
        assertThat(payload).containsEntry("text", "hello").containsEntry("page", 3);
        assertThat(payload).doesNotContainKey("type");
    }

    @Test
    void toPayloadIsCopy() {
        SessionEventRequest req = new SessionEventRequest();
        req.setType("PAGE_CHANGED");
        req.setExtra("page", 2);

        Map<String, Object> p1 = req.toPayload();
        p1.put("mutated", true);

        Map<String, Object> p2 = req.toPayload();
        assertThat(p2).doesNotContainKey("mutated");
    }
}
