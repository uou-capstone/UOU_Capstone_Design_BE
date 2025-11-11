package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.github.uou_capstone.aiplatform.domain.user.Role;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class SignUpRequestDto {
        private String email;
        private String password;
        private String fullName;
        private Role role;
        private String phoneNum;
        private LocalDate birthdate;

    // Student 전용 정보 (선생님일 경우 null)
    private Integer grade;
    private String classNumber;

    // Teacher 전용 정보 (학생일 경우 null)
    private String schoolName;
    private String department;
}



