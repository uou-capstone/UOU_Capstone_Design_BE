package io.github.uou_capstone.aiplatform.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${ai.service.base-url}")
    private String aiServiceBaseUrl;

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

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(aiServiceBaseUrl)
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

        ExchangeStrategies streamingStrategies = ExchangeStrategies.builder()
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(256 * 1024)) // 256KB
                .build();

        return WebClient.builder()
                .baseUrl(aiServiceBaseUrl)
                .exchangeStrategies(streamingStrategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private HttpClient buildBaseHttpClient() {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
    }
}