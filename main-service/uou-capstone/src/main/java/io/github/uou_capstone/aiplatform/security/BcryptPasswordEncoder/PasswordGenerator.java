package io.github.uou_capstone.aiplatform.security.BcryptPasswordEncoder;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordGenerator {
    public static void main(String[] args) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

        // 1. 원하는 비밀번호를 입력합니다.
        String rawPassword = "student1234";

        // 2. 비밀번호를 암호화합니다.
        String encodedPassword = passwordEncoder.encode(rawPassword);

        // 3. 콘솔에 출력된 암호화된 값을 복사합니다.
        System.out.println("password : "+ encodedPassword);
    }
}
