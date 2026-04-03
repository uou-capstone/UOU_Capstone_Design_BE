package io.github.uou_capstone.aiplatform.integration.fastapi;

/**
 * FastAPI /api/v2/lectures/generate-stream NDJSON 한 줄을 Spring SSE로 옮길 때의 구분.
 * <ul>
 *   <li>{@link Kind#THOUGHT} — 사고 요약 구간 → SSE {@code event: thought}, JSON {@code type: thought_delta}</li>
 *   <li>{@link Kind#MAIN} — 최종 답변 본문 → SSE {@code event: message}, JSON {@code type: delta}</li>
 * </ul>
 */
public record LectureStreamChunk(Kind kind, String delta) {

    public enum Kind {
        THOUGHT,
        MAIN
    }
}
