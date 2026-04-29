package io.github.uou_capstone.aiplatform.domain.user.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.user.dto.LoginRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.OAuthExchangeRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.RefreshTokenRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.SignUpRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.TokenResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.security.jwt.JwtTokenProvider;
import io.github.uou_capstone.aiplatform.security.jwt.RefreshTokenStore;
import io.github.uou_capstone.aiplatform.security.oauth.OAuthExchangeStore;
import io.github.uou_capstone.aiplatform.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenStore refreshTokenStore;
    private final OAuthExchangeStore oauthExchangeStore;


    /**
     * 회원가입 메서드
     */
    @Transactional
    public Long signup(SignUpRequestDto requestDto) {
        if (userRepository.findByEmail(requestDto.getEmail()).isPresent()) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_EMAIL);
        }

        String encodedPassword = passwordEncoder.encode(requestDto.getPassword());

        User user = User.builder()
                .email(requestDto.getEmail())
                .password(encodedPassword)
                .fullName(requestDto.getFullName())
                .role(requestDto.getRole())
                .phoneNum(requestDto.getPhoneNum())
                .birthDate(requestDto.getBirthdate())
                .build();

        User savedUser = userRepository.save(user);

        if (requestDto.getRole() == Role.STUDENT) {
            Student student = Student.builder()
                    .user(savedUser)
                    .grade(requestDto.getGrade() != null ? requestDto.getGrade() : 0)
                    .classNumber(requestDto.getClassNumber() != null ? requestDto.getClassNumber() : "반 미지정")
                    .build();
            studentRepository.save(student);

        } else if (requestDto.getRole() == Role.TEACHER) {
            Teacher teacher = Teacher.builder()
                    .user(savedUser)
                    .schoolName(requestDto.getSchoolName() != null ? requestDto.getSchoolName() : "학교 미지정")
                    .department(requestDto.getDepartment() != null ? requestDto.getDepartment() : "학과 미지정")
                    .build();
            teacherRepository.save(teacher);

        }

        return savedUser.getId();
    }

    /**
     * 로그인 메서드 — refresh 발급 시 jti 화이트리스트에 등록.
     */
    @Transactional
    public TokenResponseDto login(LoginRequestDto requestDto) {
        User user = userRepository.findByEmail(requestDto.getEmail())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(requestDto.getPassword(), user.getPassword())) {
            throw new BusinessException(CommonErrorCode.LOGIN_FAILED);
        }

        return issueTokens(user);
    }

    /**
     * 로그아웃 — access 블랙리스트 + 해당 user 의 모든 refresh 무효화 (단일 디바이스 정책).
     */
    @Transactional
    public void logout(String accessToken) {
        long remainingTime = jwtTokenProvider.getRemainingExpirationTime(accessToken);
        if (remainingTime > 0) {
            tokenBlacklistService.addToBlacklist(accessToken, remainingTime);
        }

        try {
            String email = jwtTokenProvider.getEmailFromToken(accessToken);
            userRepository.findByEmail(email).ifPresent(u -> refreshTokenStore.revokeAllForUser(u.getId()));
        } catch (Exception e) {
            log.warn("Failed to revoke refresh tokens on logout", e);
        }
    }

    /**
     * Refresh 회전: 매번 새 accessToken 과 새 refreshToken 발급.
     * 기존 jti 가 화이트리스트에 없으면 재사용 의심으로 판단 → 해당 user 의 모든 refresh 무효화.
     */
    @Transactional
    public TokenResponseDto refreshToken(RefreshTokenRequestDto requestDto) {
        String refreshToken = requestDto.getRefreshToken();

        try {
            jwtTokenProvider.validateToken(refreshToken);

            String oldJti = jwtTokenProvider.getJtiFromToken(refreshToken);
            if (oldJti == null || oldJti.isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.");
            }

            String email = jwtTokenProvider.getEmailFromToken(refreshToken);
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

            if (!refreshTokenStore.isValid(user.getId(), oldJti)) {
                log.warn("Refresh token replay detected: userId={}", user.getId());
                refreshTokenStore.revokeAllForUser(user.getId());
                throw new BusinessException(CommonErrorCode.INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.");
            }

            refreshTokenStore.revoke(user.getId(), oldJti);
            return issueTokens(user);

        } catch (BusinessException e) {
            throw e;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new BusinessException(CommonErrorCode.TOKEN_EXPIRED, "리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        } catch (io.jsonwebtoken.JwtException e) {
            throw new BusinessException(CommonErrorCode.INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.");
        }
    }

    /**
     * OAuth one-time code → 토큰 발급. 60초 내 1회 한정으로 store 에서 atomic 소비.
     */
    @Transactional
    public TokenResponseDto exchangeOAuthCode(OAuthExchangeRequestDto requestDto) {
        String code = requestDto == null ? null : requestDto.getCode();
        if (code == null || code.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "code 는 필수입니다.");
        }
        Optional<Long> userIdOpt = oauthExchangeStore.consume(code);
        if (userIdOpt.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_TOKEN, "유효하지 않거나 만료된 인증 코드입니다.");
        }
        User user = userRepository.findById(userIdOpt.get())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        return issueTokens(user);
    }

    private TokenResponseDto issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole().name());
        String jti = UUID.randomUUID().toString();
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail(), jti);
        refreshTokenStore.register(user.getId(), jti, jwtTokenProvider.getRefreshTokenExpirationTime());
        return new TokenResponseDto(accessToken, refreshToken);
    }
}
