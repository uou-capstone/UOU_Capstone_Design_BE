package io.github.uou_capstone.aiplatform.domain.user.dto;

import io.github.uou_capstone.aiplatform.domain.user.Role;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignUpRequestDto {
        private String email;
        private String password;
        private String fullName;
        private Role role;
    }



