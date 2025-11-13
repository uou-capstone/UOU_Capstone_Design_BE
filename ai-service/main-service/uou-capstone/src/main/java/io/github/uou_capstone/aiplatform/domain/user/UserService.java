package io.github.uou_capstone.aiplatform.domain.user;

import io.github.uou_capstone.aiplatform.domain.user.dto.MyInfoResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public MyInfoResponseDto getMyInfo() {
        // 1. SecurityContext에서 현재 로그인한 사용자의 이메일 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        // 2. 이메일로 DB에서 사용자 정보 조회
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        // 3. DTO로 변환하여 반환
        return new MyInfoResponseDto(user);
    }
}