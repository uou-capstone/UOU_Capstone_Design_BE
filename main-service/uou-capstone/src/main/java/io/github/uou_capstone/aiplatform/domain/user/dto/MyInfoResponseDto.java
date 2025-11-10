package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.github.uou_capstone.aiplatform.domain.user.Role;
import io.github.uou_capstone.aiplatform.domain.user.User;
import lombok.Getter;

import java.time.LocalDate;

@Getter
public class MyInfoResponseDto {
    private final Long userId;
    private final String email;
    private final String fullName;
    private final Role role;
    private final String phoneNum;
    private final LocalDate birthDate;

    public MyInfoResponseDto(User user) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.phoneNum = user.getPhoneNum();
        this.birthDate = user.getBirthDate();
    }
}