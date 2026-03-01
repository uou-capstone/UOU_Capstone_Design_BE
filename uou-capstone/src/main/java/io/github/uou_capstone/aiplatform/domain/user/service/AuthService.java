package io.github.uou_capstone.aiplatform.domain.user.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.user.dto.LoginRequestDto;
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
import io.github.uou_capstone.aiplatform.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final TokenBlacklistService tokenBlacklistService;


    /**
     * 회원가입 메서드
     */
    @Transactional
    public Long signup(SignUpRequestDto requestDto) {
        // 1. 이메일 중복 확인
        if (userRepository.findByEmail(requestDto.getEmail()).isPresent()) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_EMAIL);
        }

        // 2. 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(requestDto.getPassword());

        // 3. 사용자 생성
        User user = User.builder()
                .email(requestDto.getEmail())
                .password(encodedPassword)
                .fullName(requestDto.getFullName())
                .role(requestDto.getRole())
                .phoneNum(requestDto.getPhoneNum())
                .birthDate(requestDto.getBirthdate())
                .build();

        // 4. 사용자 저장
        User savedUser = userRepository.save(user);

        // 5. Role에 따라 테이블 저장
        if (requestDto.getRole() == Role.STUDENT) {
            Student student = Student.builder()
                    .user(savedUser)
                    .grade(requestDto.getGrade()!= null ? requestDto.getGrade() : 0)
                    .classNumber(requestDto.getClassNumber()!= null ? requestDto.getClassNumber() : "반 미지정")
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
     * 로그인 메서드
     */
    @Transactional
    public TokenResponseDto login(LoginRequestDto requestDto) {
        // 1. 이메일로 사용자 조회
        User user = userRepository.findByEmail(requestDto.getEmail())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LOGIN_FAILED));

        // 2. 비밀번호 일치 여부 확인
        if (!passwordEncoder.matches(requestDto.getPassword(), user.getPassword())) {
            throw new BusinessException(CommonErrorCode.LOGIN_FAILED);
        }

        // 3. 액세스 토큰과 리프레시 토큰 생성
        String accessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getEmail());

        //(선택) 리프레시 토큰을 DB에 저장하는 로직을 추가할 수 있습니다.
//        user.updateRefreshToken(refreshToken);
//        userRepository.save(user);

        // 4. 생성된 토큰들을 DTO에 담아 반환
        return new TokenResponseDto(accessToken, refreshToken);
    }

    /**
     * 로그아웃 메서드
     * 현재 사용 중인 액세스 토큰을 블랙리스트에 추가합니다.
     */
    @Transactional
    public void logout(String accessToken) {
        // 토큰 만료 시간까지 남은 시간 계산
        long remainingTime = jwtTokenProvider.getRemainingExpirationTime(accessToken);
        
        // 블랙리스트에 추가 (만료 시간까지 유지)
        if (remainingTime > 0) {
            tokenBlacklistService.addToBlacklist(accessToken, remainingTime);
        }
    }

    /**
     * 토큰 갱신 메서드
     * 리프레시 토큰을 사용하여 새로운 액세스 토큰을 발급합니다.
     */
    @Transactional
    public TokenResponseDto refreshToken(RefreshTokenRequestDto requestDto) {
        String refreshToken = requestDto.getRefreshToken();

        try {
            // 1. 리프레시 토큰 유효성 검증
            jwtTokenProvider.validateToken(refreshToken);

            // 2. 리프레시 토큰에서 이메일 추출
            String email = jwtTokenProvider.getEmailFromToken(refreshToken);

            // 3. 사용자 조회
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

            // 4. 새로운 액세스 토큰 생성
            String newAccessToken = jwtTokenProvider.createAccessToken(user.getEmail(), user.getRole().name());

            // 5. 리프레시 토큰은 그대로 유지 (또는 새로 발급할 수도 있음)
            // 여기서는 기존 리프레시 토큰을 그대로 사용
            return new TokenResponseDto(newAccessToken, refreshToken);

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new BusinessException(CommonErrorCode.TOKEN_EXPIRED, "리프레시 토큰이 만료되었습니다. 다시 로그인해주세요.");
        } catch (io.jsonwebtoken.JwtException e) {
            throw new BusinessException(CommonErrorCode.INVALID_TOKEN, "유효하지 않은 리프레시 토큰입니다.");
        }
    }
}
