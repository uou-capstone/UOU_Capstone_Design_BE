package io.github.uou_capstone.aiplatform.domain.user;

import io.github.uou_capstone.aiplatform.domain.user.dto.LoginRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.SignUpRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.TokenResponseDto;
import io.github.uou_capstone.aiplatform.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider; // (나중에 추가할 JWT 토큰 제공자)
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;


    @Transactional
    public Long signup(SignUpRequestDto requestDto) {
        // 1. 이메일 중복 확인
        if (userRepository.findByEmail(requestDto.getEmail()).isPresent()) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
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
                .orElseThrow(() -> new IllegalArgumentException("가입되지 않은 이메일입니다."));

        // 2. 비밀번호 일치 여부 확인
        if (!passwordEncoder.matches(requestDto.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
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
}