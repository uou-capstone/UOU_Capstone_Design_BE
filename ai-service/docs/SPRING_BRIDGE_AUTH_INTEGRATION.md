# Spring Bridge Auth Integration

FastAPI `/bridge/*` endpoint는 운영/공유 환경에서 `X-AI-SECRET-KEY` 헤더를 요구한다. Spring Boot는 FastAPI를 호출하는 모든 WebClient에 이 헤더를 전역 적용해야 한다.

## FastAPI 요구사항

모든 Spring -> FastAPI bridge 요청:

```http
X-AI-SECRET-KEY: <AI_SECRET_KEY>
```

FastAPI 인증 예외:

- `/health`
- `/docs`
- `/redoc`
- `/openapi.json`
- `OPTIONS` preflight

FastAPI 운영 정책:

- `APP_ENV=prod`, `APP_ENV=production`, `ENV=prod`, `ENV=production` 중 하나면 `AI_SECRET_KEY`가 필수다.
- `AI_SECRET_KEY`가 비어 있거나 sample placeholder면 FastAPI 앱 시작이 실패한다.

## Spring 설정 예시

`application.yml`:

```yaml
ai:
  service:
    base-url: ${AI_SERVICE_BASE_URL:http://localhost:8000}
    secret-key: ${AI_SECRET_KEY}
```

환경 변수:

```bash
AI_SERVICE_BASE_URL=http://localhost:8000
AI_SECRET_KEY=<same-value-as-fastapi>
```

## WebClient 전역 헤더 적용

일반 JSON 호출용 WebClient와 NDJSON streaming 호출용 WebClient 모두에 적용한다. 일부 메서드에서만 `.header(...)`를 붙이면 신규 endpoint 추가 시 누락될 수 있으므로 `defaultHeader(...)`로 전역 적용한다.

```java
@Configuration
public class WebClientConfig {

    @Value("${ai.service.base-url}")
    private String aiServiceBaseUrl;

    @Value("${ai.service.secret-key}")
    private String aiSecretKey;

    @PostConstruct
    void validateAiSecretKey() {
        if (aiSecretKey == null || aiSecretKey.isBlank()) {
            throw new IllegalStateException("ai.service.secret-key must be configured");
        }
    }

    @Bean
    public WebClient aiServiceWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(aiServiceBaseUrl)
                .defaultHeader("X-AI-SECRET-KEY", aiSecretKey)
                .build();
    }

    @Bean
    public WebClient aiServiceStreamingWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(aiServiceBaseUrl)
                .defaultHeader("X-AI-SECRET-KEY", aiSecretKey)
                .build();
    }
}
```

## 적용 대상

Spring이 FastAPI 신규 기능을 호출할 때 사용하는 모든 client에 적용한다.

- 일반 JSON bridge 호출
- NDJSON streaming bridge 호출
- 신규 `FastApiBridgeClient` 메서드
- 별도 WebClient를 직접 생성하는 코드가 있다면 해당 WebClient

## 확인 체크리스트

- `aiServiceWebClient`에 `X-AI-SECRET-KEY` default header가 있다.
- `aiServiceStreamingWebClient`에 `X-AI-SECRET-KEY` default header가 있다.
- 운영 환경에서 `ai.service.secret-key`가 비어 있으면 Spring 앱 시작이 실패한다.
- FastAPI `AI_SECRET_KEY`와 Spring `ai.service.secret-key` 값이 같다.
- 헤더가 없는 요청은 FastAPI에서 `401`이 난다.
- 정상 헤더가 있는 `/bridge/*` 요청은 `200` 또는 정상 NDJSON stream을 반환한다.

## 관련 문서

- `ai-service/docs/BRIDGE_AGENT_ENDPOINTS.md`
