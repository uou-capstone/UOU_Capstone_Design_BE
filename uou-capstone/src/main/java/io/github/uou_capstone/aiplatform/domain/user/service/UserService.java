package io.github.uou_capstone.aiplatform.domain.user.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.domain.user.dto.MyInfoResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.PasswordChangeRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.dto.ProfileUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;

    @Transactional(readOnly = true)
    public MyInfoResponseDto getMyInfo() {
        // 1. SecurityContext에서 현재 로그인한 사용자의 이메일 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        // 2. 이메일로 DB에서 사용자 정보 조회
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 3. DTO로 변환하여 반환
        return new MyInfoResponseDto(user);
    }

    @Transactional
    public void changePassword(PasswordChangeRequestDto requestDto) {
        // 1. 현재 로그인한 사용자 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 현재 비밀번호 일치 확인
        if (!passwordEncoder.matches(requestDto.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException(CommonErrorCode.PASSWORD_NOT_MATCH, "현재 비밀번호가 일치하지 않습니다.");
        }

        // 3. 새 비밀번호와 확인 비밀번호 일치 확인 (클라이언트에서도 하겠지만 서버에서도 한 번 더)
        if (!requestDto.getNewPassword().equals(requestDto.getConfirmNewPassword())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "새 비밀번호와 확인 비밀번호가 일치하지 않습니다.");
        }

        // 4. 비밀번호 변경 (암호화)
        user.changePassword(passwordEncoder.encode(requestDto.getNewPassword()));
    }

    /**
     * 프로필 수정 (이름, 전화번호, 생년월일)
     */
    @Transactional
    public MyInfoResponseDto updateProfile(ProfileUpdateRequestDto requestDto) {
        // 1. 현재 로그인한 사용자 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 프로필 정보 업데이트
        if (requestDto.getFullName() != null) {
            user.update(requestDto.getFullName());
        }
        if (requestDto.getPhoneNum() != null) {
            user.updatePhoneNum(requestDto.getPhoneNum());
        }
        if (requestDto.getBirthDate() != null) {
            user.updateBirthDate(requestDto.getBirthDate());
        }

        // 3. 업데이트된 정보 반환
        return new MyInfoResponseDto(user);
    }

    /**
     * 이메일 중복 확인
     */
    @Transactional(readOnly = true)
    public boolean checkEmailAvailability(String email) {
        return !userRepository.findByEmail(email).isPresent();
    }

    /**
     * 회원 탈퇴
     */
    @Transactional
    public void deleteAccount(String password) {
        // 1. 현재 로그인한 사용자 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 비밀번호 확인
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(CommonErrorCode.PASSWORD_NOT_MATCH, "비밀번호가 일치하지 않습니다.");
        }

        // 3. 회원 삭제 (Cascade로 Student/Teacher도 함께 삭제됨)
        userRepository.delete(user);
    }
}
