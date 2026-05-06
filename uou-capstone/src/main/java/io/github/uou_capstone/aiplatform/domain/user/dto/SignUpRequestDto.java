package io.github.uou_capstone.aiplatform.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class SignUpRequestDto {

    @NotBlank(message = "이메일은 필수 입력값입니다.")
    @Email(message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수 입력값입니다.")
    private String password;

    @NotBlank(message = "이름은 필수 입력값입니다.")
    private String fullName;

    @NotNull(message = "역할은 필수 입력값입니다.")
    private Role role;

    private String phoneNum;
    private LocalDate birthdate;

    // Student 전용 정보 (선생님일 경우 null)
    private Integer grade;
    private String classNumber;

    // Teacher 전용 정보 (학생일 경우 null)
    private String schoolName;
    private String department;

    @JsonIgnore
    @AssertTrue(message = "STUDENT 가입 시 grade 와 classNumber 는 필수입니다.")
    public boolean isStudentFieldsValid() {
        if (role != Role.STUDENT) return true;
        return grade != null && classNumber != null && !classNumber.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "TEACHER 가입 시 schoolName 과 department 는 필수입니다.")
    public boolean isTeacherFieldsValid() {
        if (role != Role.TEACHER) return true;
        return schoolName != null && !schoolName.isBlank()
                && department != null && !department.isBlank();
    }
}
