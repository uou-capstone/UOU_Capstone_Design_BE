package io.github.uou_capstone.aiplatform.security.oauth;

import io.github.uou_capstone.aiplatform.domain.user.Role;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        // 1. 기본 OAuth2UserService를 통해 사용자 정보 가져오기
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 2. 카카오로부터 받은 사용자 정보 파싱하기
        Map<String, Object> attributes = oAuth2User.getAttributes();
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

        String email = (String) kakaoAccount.get("email");
        String name = (String) profile.get("nickname");

        // 3. DB에서 이메일을 통해 사용자를 찾거나, 없으면 새로 생성하기
        User user = userRepository.findByEmail(email)
                .map(entity -> entity.update(name)) // 기존 사용자는 이름 업데이트
                .orElse(createNewUser(email, name)); // 없는 사용자는 새로 생성

        userRepository.save(user);

        // 4. Spring Security가 인식할 수 있는 형태로 변환하여 반환
        return new DefaultOAuth2User(
                Collections.singleton(new SimpleGrantedAuthority(user.getRole().name())),
                attributes,
                "id"); // 카카오에서 사용하는 사용자 식별자 속성 이름
    }

    private User createNewUser(String email, String name) {
        return User.builder()
                .email(email)
                .fullName(name)
                .role(Role.STUDENT) // 기본 역할은 학생으로 설정
                .password("KAKAO_USER_PASSWORD") // 소셜 로그인이므로 실제 비밀번호는 필요 없음
                .build();
    }
}