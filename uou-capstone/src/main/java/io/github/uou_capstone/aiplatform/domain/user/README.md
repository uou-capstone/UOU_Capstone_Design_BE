# user 도메인

`User` + 역할별 프로필(`Teacher`/`Student`) + 인증(JWT) 흐름.
이 도메인 작업 시 참고용 상시 메모. 버그·성능 수정 **이력** 은 루트 `uou-capstone/DEV_NOTES.md` 참조.

---

## 개요

- **User 엔티티** (`users` 테이블): 인증 식별자. `email` (unique), `password` (BCrypt 인코딩), `fullName`, `birthDate`, `phoneNum`, `role`(Enum). `teacher`/`student` 1:1 (`cascade=ALL`)
- **Role**: `STUDENT` / `TEACHER` 2종 — `@PreAuthorize("hasAuthority('TEACHER')")` / `hasAnyAuthority('STUDENT','TEACHER')` 와 매칭
- **Teacher 엔티티** (`teachers`): `schoolName`, `department` + `User` 1:1 — Course 소유 주체
- **Student 엔티티** (`students`): `grade`, `classNumber` + `User` 1:1 — Enrollment·StudentInquiry 보유

---

## 주요 플로우

### 인증 (`AuthController`) prefix: `/api/auth`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `POST /signup` | 익명 | 회원가입 — 이메일 중복 체크 → User 저장 → Role 별 Student/Teacher row 생성 |
| `POST /login` | 익명 | 로그인 — Access + Refresh 토큰 발급 (DB 저장 X — 검증은 토큰 자체로) |
| `POST /logout` | 인증 | 현재 액세스 토큰을 **블랙리스트** 에 추가 (만료 시각까지 유지) |
| `POST /refresh` | 익명 | Refresh 토큰으로 새 Access 토큰 발급 (현재는 Refresh 회전 없음 — 그대로 재사용) |

### 사용자 (`UserController`) prefix: `/api/users`

| 메서드/경로 | 권한 | 용도 |
|---|---|---|
| `GET  /me` | 인증 | 내 정보 조회 |
| `PUT  /password` | 인증 | 비밀번호 변경 — 현재 비밀번호 검증 + 신/확인 일치 검증 |
| `PUT  /profile` | 인증 | 이름·전화번호·생년월일 수정 |
| `GET  /check-email?email=` | 익명 | 회원가입 시 중복 체크 |
| `DELETE /account` | 인증 | 회원 탈퇴 — 비밀번호 재확인 후 cascade 삭제 |

### OAuth2 (Kakao) — 별도 패키지
`security/oauth/` 하위에 핸들러 분리. 이 도메인은 OAuth 코드를 직접 보유하지 않음.
- `CustomOAuth2UserService` — 카카오 profile (`kakao_account.email`/`profile.nickname`) → `User` 조회/생성. 신규 가입자는 자동으로 `Role.STUDENT`
- `OAuth2AuthenticationSuccessHandler` — 성공 시 `${oauth2.redirect.frontend-url}${oauth2.redirect.path}?accessToken=...&refreshToken=...&success=true` 로 리다이렉트
- `OAuth2AuthenticationFailureHandler` — 실패 처리

> ⚠️ DEV_NOTES 1-2 항목: 현재 redirect URL 에 토큰을 평문 노출하는 방식 (P0). 추후 fragment/POST 방식 또는 HttpOnly 쿠키로 변경 예정 — 변경 시 프론트 `/auth/callback` 파싱 로직 동기 수정 필요.

---

## 상시 주의사항 (Gotcha)

### 사용자 조회 — `findByEmail` vs `findByEmailWithRoles`
```java
// findByEmail        — User + LEFT JOIN FETCH student + LEFT JOIN FETCH teacher (실제로는 Roles 까지 가져옴)
// findByEmailWithRoles — @EntityGraph 로 동일 결과
```
두 메서드 모두 student/teacher 까지 fetch. 차이: `findByEmail` 은 JPQL JOIN FETCH, `findByEmailWithRoles` 는 EntityGraph. 새 메서드 추가 시 **반드시 student/teacher 함께 fetch** 하지 않으면 N+1 폭발.

**프로젝트 규칙 (CLAUDE.md):** 일반 코드에서는 `currentUserResolver.getUser()` 만 사용. `findByEmail` 직접 호출 금지. 단 **`@Async` / SecurityContext 미전파 영역** 에서는 `findByEmailWithRoles(email)` 1회 허용.
> 현재 `UserService` 자체는 `SecurityContextHolder` + `findByEmail` 을 직접 사용 — 이 도메인은 인증 자체를 다루므로 예외 (resolver 도 결국 이 메서드를 호출).

### 회원가입 시 Role 분기 누락 방지
`SignUpRequestDto.role` 이 `STUDENT` 면 grade/classNumber, `TEACHER` 면 schoolName/department 가 필요. 누락 시 안전한 기본값으로 폴백 (`"반 미지정"` / `"학교 미지정"`) — DB NOT NULL 제약 회피용. **요구사항 강화될 때 폴백 제거 + DTO Validation 추가 필요**.

### Refresh 토큰 회전 미구현
- `refreshToken()` 은 Access 만 새로 발급, Refresh 는 그대로 반환
- DEV_NOTES 1-8 (P1): 회전 도입 시 프론트가 매 refresh 응답에서 새 토큰을 저장해야 함 — 합의 후 진행
- DB 저장도 X — 단순 검증만. 강제 로그아웃(특정 사용자 모든 Refresh 무효화) 불가

### 토큰 블랙리스트 = Redis
`tokenBlacklistService.addToBlacklist(token, remainingTime)` 으로 만료까지의 시간만큼만 보관. 만료 후 자동 정리. 블랙리스트 검사는 `JwtAuthenticationFilter` 에서 매 요청 1회.

### Refresh 토큰 만료 시 에러 매핑
```java
catch (ExpiredJwtException e) → BusinessException(TOKEN_EXPIRED, "리프레시 토큰이 만료되었습니다...")
catch (JwtException e)        → BusinessException(INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.")
```
Access 검증은 `JwtAuthenticationFilter` 에서 처리. **둘 다 `BusinessException` 으로 던질 것** — `RuntimeException` 던지면 필터의 catch 에 안 잡혀 4010 오진단.

### 1:1 매핑 + cascade 주의
- `User.teacher` / `User.student` 는 1:1 + `cascade=ALL` — `userRepository.delete(user)` 한 번이면 cascade 로 자식 정리
- 단 자식 측 `OneToMany` (`Student.studentInquiries`) 는 별도 cascade — 학생 삭제 시 inquiries 자동 정리됨
- `Course` 는 Teacher 가 소유하지만 Course 의 cascade 는 Course → Lecture/Enrollment 까지. **Teacher 를 직접 삭제하면 Course 가 끊긴 상태로 남을 수 있음** — 회원 탈퇴 시나리오 강화 필요 시 별도 처리

### `findByUser_Id` 명명 규칙 — 그대로 유지
Spring Data JPA 의 underscore-property 표기. `findByUserId` 로 바꾸면 인식 실패 — 기존 호출부 다 영향. 의도적 표기.

### 비밀번호 정책 미적용
- 현재 `SignUpRequestDto`/`PasswordChangeRequestDto` 에 길이/복잡도 검증 없음
- DEV_NOTES 1-5 (P3): 정책 도입 시 `@Pattern` 또는 커스텀 Validator 추가

---

## 주요 파일

### Controller
- `controller/AuthController.java` — signup/login/logout/refresh
- `controller/UserController.java` — me/password/profile/check-email/delete

### Service
- `service/AuthService.java` (160줄) — 회원가입·로그인·로그아웃(블랙리스트)·토큰 갱신
- `service/UserService.java` (112줄) — 내 정보 / 비번 변경 / 프로필 수정 / 탈퇴

### Repository
- `repository/UserRepository.java` — `findByEmail` (JPQL fetch), `findByEmailWithRoles` (EntityGraph fetch)
- `repository/TeacherRepository.java` — `findByUser_Id`
- `repository/StudentRepository.java` — `findByUser_Id`

### Entity
- `entity/User.java` — 인증 식별자 + 1:1 (teacher/student)
- `entity/Teacher.java` — `schoolName`, `department`
- `entity/Student.java` — `grade`, `classNumber`, `studentInquiries` 컬렉션
- `entity/Role.java` — STUDENT / TEACHER

### DTO
- `dto/SignUpRequestDto.java`, `LoginRequestDto.java`, `TokenResponseDto.java`, `RefreshTokenRequestDto.java`
- `dto/MyInfoResponseDto.java`, `ProfileUpdateRequestDto.java`, `PasswordChangeRequestDto.java`, `AccountDeleteRequestDto.java`

### 관련 외부 (이 도메인 밖)
- `security/jwt/JwtTokenProvider.java` — Access/Refresh 토큰 생성·검증
- `security/jwt/JwtAuthenticationFilter.java` — 매 요청 토큰 검증 + 블랙리스트 검사
- `service/CurrentUserResolver.java` — `@RequestScope` 캐시된 User/Teacher/Student 조회 (도메인 코드는 이걸 우선 사용)
- `service/TokenBlacklistService.java` — Redis 기반 블랙리스트
- `security/oauth/` — Kakao OAuth2 처리
