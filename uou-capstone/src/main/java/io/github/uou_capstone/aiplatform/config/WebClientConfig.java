package io.github.uou_capstone.aiplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    private static final String AI_SECRET_HEADER = "X-AI-SECRET-KEY";

    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");

    /** prod 부팅을 차단할 알려진 placeholder 값들 (로컬 dummy 값 포함). */
    private static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "YOUR_SUPER_SECRET_AI_KEY_12345",
            "YOUR_AI_SECRET_KEY",
            "CHANGE_ME",
            "changeme",
            "placeholder"
    );

    @Value("${ai.service.base-url}")
    private String aiServiceBaseUrl;

    @Value("${ai.service.secret-key:}")
    private String aiSecretKey;

    private final Environment environment;
    private final ObjectMapper objectMapper;

    public WebClientConfig(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void validateAiSecretKey() {
        boolean isProd = false;
        for (String profile : environment.getActiveProfiles()) {
            if (PROD_PROFILES.contains(profile.toLowerCase())) {
                isProd = true;
                break;
            }
        }

        boolean blank = aiSecretKey == null || aiSecretKey.isBlank();
        boolean placeholder = !blank && PLACEHOLDER_SECRETS.contains(aiSecretKey);

        if (isProd && (blank || placeholder)) {
            throw new IllegalStateException(
                    "ai.service.secret-key must be set to a real value in prod profile " +
                            "(current value is " + (blank ? "blank" : "a known placeholder") + "). " +
                            "Set AI_SERVICE_SECRET_KEY to the same value as FastAPI AI_SECRET_KEY.");
        }
        if (blank) {
            log.warn("ai.service.secret-key is blank — FastAPI /bridge/* calls will be sent without {} header. " +
                    "This is only valid in local environments where FastAPI auth is disabled.", AI_SECRET_HEADER);
        } else if (placeholder) {
            log.warn("ai.service.secret-key is a known placeholder ({}). FastAPI will reject this in production.", aiSecretKey);
        }
    }

    /**
     * 일반 API 호출용 WebClient (bodyToMono 등 집계 응답).
     * maxInMemorySize 10MB: FastAPI 상태 응답에 마크다운 전문이 포함될 수 있음.
     */
    @Bean
    public WebClient aiServiceWebClient() {
        HttpClient httpClient = buildBaseHttpClient()
                .responseTimeout(Duration.ofSeconds(300))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(300, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(300, TimeUnit.SECONDS)));

        ExchangeStrategies strategies = aiExchangeStrategies(10 * 1024 * 1024);

        return WebClient.builder()
                .baseUrl(aiServiceBaseUrl)
                .defaultHeader(AI_SECRET_HEADER, secretHeaderValue())
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * SSE/NDJSON 스트리밍 전용 WebClient (bodyToFlux).
     * - HTTP/1.1 강제: HTTP/2 멀티플렉싱으로 인한 프레임 버퍼링 방지.
     * - maxInMemorySize 256KB: 스트리밍은 라인 단위로 처리하므로 작은 크기로 충분.
     *   큰 버퍼로 설정하면 StringDecoder가 라인을 모아서 한꺼번에 방출할 수 있음.
     */
    @Bean
    public WebClient aiServiceStreamingWebClient() {
        HttpClient httpClient = buildBaseHttpClient()
                .protocol(HttpProtocol.HTTP11)  // HTTP/2 비활성화 → 청크 단위 즉시 전달
                .responseTimeout(Duration.ofSeconds(600))
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(600, TimeUnit.SECONDS))
                                .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        ExchangeStrategies streamingStrategies = aiExchangeStrategies(256 * 1024);

        return WebClient.builder()
                .baseUrl(aiServiceBaseUrl)
                .defaultHeader(AI_SECRET_HEADER, secretHeaderValue())
                .exchangeStrategies(streamingStrategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private HttpClient buildBaseHttpClient() {
        ConnectionProvider provider = ConnectionProvider.builder("ai-service")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        return HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
    }

    private ExchangeStrategies aiExchangeStrategies(int maxInMemorySize) {
        return ExchangeStrategies.builder()
                .codecs(cfg -> {
                    cfg.defaultCodecs().maxInMemorySize(maxInMemorySize);
                    cfg.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
                    cfg.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
                })
                .build();
    }

    /** blank secret이면 빈 문자열을 헤더 값으로 둔다 — FastAPI auth disabled 환경에서만 허용. */
    private String secretHeaderValue() {
        return aiSecretKey == null ? "" : aiSecretKey;
    }
}
