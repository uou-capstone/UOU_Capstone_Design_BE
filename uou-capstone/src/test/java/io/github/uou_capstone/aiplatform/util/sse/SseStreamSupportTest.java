package io.github.uou_capstone.aiplatform.util.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SseStreamSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SseStreamPolicy policyNoDone() {
        return SseStreamPolicy.builder()
                .idleTimeout(Duration.ofSeconds(5))
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
    }

    private List<ServerSentEvent<Map<String, Object>>> collect(Flux<String> in, SseStreamPolicy policy) {
        return SseStreamSupport.wrapNdjsonByType(in, objectMapper, policy, null)
                .collectList()
                .block();
    }

    @Test
    @DisplayName("thought_delta / answer_delta / done 라인이 각각 동일 이름의 SSE event 로 매핑")
    void typeFieldMappedToSseEventName() {
        Flux<String> in = Flux.just(
                "{\"type\":\"thought_delta\",\"text\":\"think\"}",
                "{\"type\":\"answer_delta\",\"text\":\"answer\"}",
                "{\"type\":\"done\",\"data\":{\"x\":1}}"
        );

        List<ServerSentEvent<Map<String, Object>>> events = collect(in, policyNoDone());

        assertThat(events).hasSize(3);
        assertThat(events.get(0).event()).isEqualTo("thought_delta");
        assertThat(events.get(0).data()).containsEntry("text", "think");
        assertThat(events.get(1).event()).isEqualTo("answer_delta");
        assertThat(events.get(1).data()).containsEntry("text", "answer");
        assertThat(events.get(2).event()).isEqualTo("done");
        assertThat(events.get(2).data()).containsKey("data");
    }

    @Test
    @DisplayName("error 라인의 details.errorType 은 SSE forward 에서 strip — 내부 정보 노출 금지")
    void errorLineStripsDetailsErrorType() {
        String errorLine = "{\"type\":\"error\",\"code\":\"AI_SERVER_ERROR\","
                + "\"message\":\"failed\","
                + "\"details\":{\"errorType\":\"GeminiQuotaExceeded\",\"foo\":\"bar\"}}";

        List<ServerSentEvent<Map<String, Object>>> events = collect(Flux.just(errorLine), policyNoDone());

        assertThat(events).hasSize(1);
        ServerSentEvent<Map<String, Object>> e = events.get(0);
        assertThat(e.event()).isEqualTo("error");
        assertThat(e.data()).containsEntry("code", "AI_SERVER_ERROR");

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) e.data().get("details");
        assertThat(details).doesNotContainKey("errorType");
        assertThat(details).containsEntry("foo", "bar");
    }

    @Test
    @DisplayName("error.details 가 errorType 만 있으면 details 전체 제거")
    void errorDetailsWithOnlyErrorTypeRemovedEntirely() {
        String errorLine = "{\"type\":\"error\",\"code\":\"X\",\"message\":\"m\","
                + "\"details\":{\"errorType\":\"SomeException\"}}";

        List<ServerSentEvent<Map<String, Object>>> events = collect(Flux.just(errorLine), policyNoDone());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).doesNotContainKey("details");
    }

    @Test
    @DisplayName("파싱 실패 라인은 message 이벤트 + raw 페이로드로 fallback")
    void unparseableLineFallsBackToMessageEvent() {
        List<ServerSentEvent<Map<String, Object>>> events = collect(
                Flux.just("this is not json"), policyNoDone());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("message");
        assertThat(events.get(0).data()).containsEntry("raw", "this is not json");
    }

    @Test
    @DisplayName("type 필드 없는 라인도 message 이벤트로 그대로 forward")
    void lineWithoutTypeUsesMessageEvent() {
        List<ServerSentEvent<Map<String, Object>>> events = collect(
                Flux.just("{\"foo\":\"bar\"}"), policyNoDone());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("message");
        assertThat(events.get(0).data()).containsEntry("foo", "bar");
    }

    @Test
    @DisplayName("blank 라인은 필터링되어 emit 안 됨")
    void blankLinesAreFiltered() {
        List<ServerSentEvent<Map<String, Object>>> events = collect(
                Flux.just("", "  ", "{\"type\":\"done\"}"), policyNoDone());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("done");
    }

    @Test
    @DisplayName("appendDoneOnComplete=true 면 마지막에 done 이벤트 추가")
    void appendDoneOnCompleteTrueEmitsExtraDone() {
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(Duration.ofSeconds(5))
                .appendDoneOnComplete(true)
                .build();

        List<ServerSentEvent<Map<String, Object>>> events = collect(
                Flux.just("{\"type\":\"answer_delta\",\"text\":\"x\"}"), policy);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).event()).isEqualTo("answer_delta");
        assertThat(events.get(1).event()).isEqualTo("done");
        assertThat(events.get(1).data()).isEmpty();
    }
}
