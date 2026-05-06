# security

JWT 인증 + OAuth2(카카오) + 401/403 응답 표준화. Spring Security `FilterChain` 의 핵심 부품 모음.

---

## 패키지 구조

| 하위 | 역할 |
|---|---|
| `jwt/` | Access/Refresh 토큰 발급·검증 + 매 요청 필터 |
| `oauth/` | 카카오 OAuth2 로그인 흐름 + 성공/실패 핸들러 |
| `exception/` | 인증 실패(401)·인가 실패(403) JSON 응답 표준화 |

---

## JWT 흐름

### 발급 (`JwtTokenProvider`)
- `createAccessToken(email, role)` — subject=email, claim `role`. 만료 `${jwt.access-token-expiration-time}`
- `createRefreshToken(email, jti)` — role claim 없음, claim `jti` 포함. 만료 `${jwt.refresh-token-expiration-time}`
- 둘 다 HMAC-SHA 키 (`${jwt.secret}` BASE64 디코딩)

### 검증 — `JwtAuthenticationFilter` (`OncePerRequestFilter`)
매 요청 1회:
1. `OPTIONS` 메서드는 통과 (CORS preflight 우회 — `shouldNotFilter`)
2. `Authorization: Bearer <token>` 헤더에서 토큰 추출
3. 토큰 있으면 — **블랙리스트 검사** (`tokenBlacklistService.isBlacklisted`)
4. 통과하면 `validateToken()` → `getAuthentication()` → `SecurityContext` 에 저장
5. 예외 → `request.setAttribute("exception", "TOKEN_EXPIRED" | "INVALID_TOKEN")` 후 chain 통과 — **401 응답은 `RestAuthenticationEntryPoint` 가 던짐**

### 만료 시간 헬퍼
- `getRemainingExpirationTime(token)` — 로그아웃 시 블랙리스트 TTL 계산용 (만료까지의 ms 만큼만 보관)

---

## OAuth2 (카카오) 흐름

> ⚠️ **현재 구현은 Kakao 전용**. `user/README.md` 가 "Google" 로 적힌 부분은 부정확 — 실제는 Kakao. 사용자 README 수정 후보.

### 1. 사용자 정보 조회 — `CustomOAuth2UserService` (`extends DefaultOAuth2UserService`)
- 카카오 OpenID 응답에서 `kakao_account.email` + `kakao_account.profile.nickname` 파싱
- DB 에서 email 로 사용자 조회:
  - 있음 → `entity.update(name)` (이름 갱신)
  - 없음 → 새 `User` 생성 (`role=STUDENT`, `password="KAKAO_USER_PASSWORD"` 자리표시자)
- `DefaultOAuth2User` 반환 (authority = role 1개)

### 2. 성공 핸들러 — `OAuth2AuthenticationSuccessHandler`
- **one-time code** 발급 → `OAuthExchangeStore` 에 (code → userId) 매핑을 60초 TTL 로 저장
- 프론트로 리다이렉트 — `${oauth2.redirect.frontend-url}${oauth2.redirect.path}?code=<one-time>&success=true`
- 기본값: `http://localhost:3000/auth/callback`
- 프론트는 받은 `code` 로 `POST /api/auth/oauth/exchange` 호출 → 토큰 교환 (1회 atomic 소비, 평문 토큰이 URL 에 노출되지 않음)

### 3. 실패 핸들러 — `OAuth2AuthenticationFailureHandler`
- 동일 redirect path 로 `?success=false&error=<msg>`

---

## 401/403 응답 표준화

`ErrorResponse` JSON (status·error·code·message·path) 로 통일.

### `RestAuthenticationEntryPoint` (401)
필터에서 설정한 `request.getAttribute("exception")` 값에 따라 분기:

| attribute | 매핑 ErrorCode |
|---|---|
| `TOKEN_EXPIRED` | `CommonErrorCode.TOKEN_EXPIRED` |
| `INVALID_TOKEN` | `CommonErrorCode.INVALID_TOKEN` |
| (그 외) | `CommonErrorCode.UNAUTHORIZED` |

### `JwtAccessDeniedHandler` (403)
인증된 사용자지만 권한 부족 — `CommonErrorCode.FORBIDDEN` 으로 통일.

---

## SecurityConfig 와의 관계

`config/SecurityConfig` 가 위 부품들을 조립:
- `addFilterBefore(JwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
- `exceptionHandling.authenticationEntryPoint(RestAuthenticationEntryPoint)` + `accessDeniedHandler(JwtAccessDeniedHandler)`
- `oauth2Login.userInfoEndpoint().userService(CustomOAuth2UserService)`
- `oauth2Login.successHandler(OAuth2AuthenticationSuccessHandler)` + `failureHandler(OAuth2AuthenticationFailureHandler)`
- 공개 경로: `/api/auth/**` (이메일 중복 확인 포함), OAuth2 콜백 등

---

## 상시 주의사항 (Gotcha)

### 401 발생 경로 디버깅
`JwtTokenProvider.getAuthentication` 은 role 클레임이 없으면 `BusinessException(CommonErrorCode.INVALID_TOKEN, "권한 정보가 없는 토큰입니다.")` 를 던지고, `JwtAuthenticationFilter` 의 `catch (BusinessException)` 가 받아 `INVALID_TOKEN` 속성을 세팅. 매핑 결과:
- Refresh 토큰을 Access 자리에 넣었을 때 → 401 INVALID_TOKEN
- 만료된 Access 토큰 → 401 TOKEN_EXPIRED
- 위조/포맷 오류 → 401 INVALID_TOKEN

### 블랙리스트 검사 순서
**`validateToken` 보다 먼저** 블랙리스트 검사. 만료된 토큰이라도 블랙리스트에 있으면 만료 에러가 아닌 INVALID_TOKEN — 의도된 (보안상 만료 사실 노출 최소화).

### 토큰 만료 시간 단위
`accessTokenExpirationTime` / `refreshTokenExpirationTime` 는 **밀리초** (`Date(now + ms)`). `application.yml` 에 `1800000` (30분) 같이 ms 로 표기.

### Refresh 회전 (구현됨)
`AuthService.refreshToken` 은 매 호출마다 새 Access + 새 Refresh 발급. 이전 Refresh 의 `jti` 는 `RefreshTokenStore` 에서 revoke. 화이트리스트에 없는 `jti` 가 들어오면 **재사용 의심** 으로 판단해 해당 user 의 모든 Refresh 무효화. FE 는 매 refresh 응답에서 새 토큰을 반드시 저장해야 함.

### OAuth2 로그인 시 비밀번호 자리표시자
`CustomOAuth2UserService.createNewUser` 가 `password = "KAKAO_USER_PASSWORD"` 평문 저장. **`PasswordEncoder` 거치지 않음** → 일반 로그인 흐름과 호환 안 됨 (의도된 — OAuth 사용자는 비번 로그인 불가). 하지만 같은 컬럼을 공유하므로 UI 비밀번호 변경 화면에 OAuth 사용자가 진입하면 부적절. 분리 처리 필요.

### `Role.STUDENT` 강제 매핑
카카오 신규 가입자는 무조건 STUDENT. 교사 OAuth 가입을 허용하려면 `loadUser` 에서 분기 또는 가입 후 별도 role 변경 흐름 추가.

### `OAuth2AuthenticationSuccessHandler` — one-time code 교환 방식 (구현됨)
이전 평문 토큰 URL 노출(DEV_NOTES 1-2, P0)은 해소됨. 현재는 일회용 `code` 만 URL 에 실리고, FE 가 `POST /api/auth/oauth/exchange` 로 토큰을 교환. 보안 향상을 더 원하면 추후 HttpOnly 쿠키 + CSRF 로 추가 강화 가능.

### `JwtTokenProvider.validateToken` 에서 `RuntimeException` 그대로 throw
프로젝트 컨벤션은 `BusinessException` 권장이나, 이 메서드는 `JwtAuthenticationFilter` 가 catch 해서 attribute 로 변환. 직접 호출하는 코드(`AuthService.refreshToken`)도 catch 해서 BusinessException 으로 매핑 — 두 경로 모두 안전.

### CORS preflight 우회
`shouldNotFilter` 가 OPTIONS 만 통과시킴. 다른 메서드 추가하면 인증 우회 — 절대 확장하지 말 것.

---

## 주요 파일

### JWT
- `jwt/JwtTokenProvider.java` — Access/Refresh 발급·검증·이메일 추출·만료 잔여시간
- `jwt/JwtAuthenticationFilter.java` — `OncePerRequestFilter` (블랙리스트 → validate → SecurityContext)

### OAuth2
- `oauth/CustomOAuth2UserService.java` — 카카오 사용자 정보 → User 조회/생성
- `oauth/OAuth2AuthenticationSuccessHandler.java` — JWT 발급 + FE 리다이렉트
- `oauth/OAuth2AuthenticationFailureHandler.java` — 실패 시 FE 리다이렉트

### 예외 응답 표준화
- `exception/RestAuthenticationEntryPoint.java` — 401 JSON 응답
- `exception/JwtAccessDeniedHandler.java` — 403 JSON 응답

### 의존
- `service/TokenBlacklistService.java` — Redis 기반 로그아웃 토큰 블랙리스트 (도메인 외부 service)
- `domain/user/repository/UserRepository.java` — `findByEmail`
- `domain/user/entity/{User, Role}` — OAuth 신규 가입
- `common/dto/ErrorResponse.java` — 표준 에러 페이로드
- `common/error/CommonErrorCode.{TOKEN_EXPIRED, INVALID_TOKEN, UNAUTHORIZED, FORBIDDEN}`
- `config/SecurityConfig.java` — 위 부품들의 조립부 (도메인 외부)

### 설정 (application.yml)
- `jwt.secret`, `jwt.access-token-expiration-time`, `jwt.refresh-token-expiration-time`
- `oauth2.redirect.frontend-url`, `oauth2.redirect.path`
- `spring.security.oauth2.client.registration.kakao.*` — Kakao OAuth 클라이언트 설정
