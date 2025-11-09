package io.github.uou_capstone.aiplatform.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    // application.yml에서 ai-service의 기본 주소를 가져옴
    @Value("${ai.service.base-url}")
    private String aiServiceBaseUrl;

    @Bean
    public WebClient aiServiceWebClient() {
        // ✅ 타임아웃 설정을 위한 HttpClient 정의
        HttpClient httpClient = HttpClient.create()
                // 연결 타임아웃 (예: 5초)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(300)) // ✅ 응답 타임아웃 (예: 5분)
                .doOnConnected(conn ->
                        conn.addHandlerLast(new ReadTimeoutHandler(300, TimeUnit.SECONDS)) // 읽기 타임아웃
                                .addHandlerLast(new WriteTimeoutHandler(300, TimeUnit.SECONDS)) // 쓰기 타임아웃
                );

        return WebClient.builder()
                .baseUrl(aiServiceBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient)) // ✅ 타임아웃 설정 적용
                .build();
    }
}