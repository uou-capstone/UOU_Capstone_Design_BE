package io.github.uou_capstone.aiplatform.util;

import org.slf4j.Logger;

import java.util.Map;

/**
 * FastAPI 브리지/프록시 응답 디버깅용 로깅.
 * <p>
 * DEBUG 레벨에서만 동작하며, 긴 본문은 잘라서 기록한다.
 * 운영에서 상세 본문이 필요하면 {@code logging.level...=DEBUG} 로 켠다.
 */
public final class BridgeResponseLogger {

    private static final int MAX_BODY_CHARS = 2048;

    private BridgeResponseLogger() {
    }

    public static void debugBody(Logger log, String endpointLabel, String body) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (body == null) {
            log.debug("{} response: <null>", endpointLabel);
            return;
        }
        int len = body.length();
        String snippet = len > MAX_BODY_CHARS
                ? body.substring(0, MAX_BODY_CHARS) + "...(truncated, totalChars=" + len + ")"
                : body;
        log.debug("{} response ({} chars): {}", endpointLabel, len, snippet);
    }

    public static void debugMapSummary(Logger log, String endpointLabel, Map<?, ?> map) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (map == null) {
            log.debug("{} response: <null map>", endpointLabel);
            return;
        }
        log.debug("{} response: size={}, keys={}", endpointLabel, map.size(), map.keySet());
    }
}
