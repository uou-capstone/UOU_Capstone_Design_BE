package io.github.uou_capstone.aiplatform.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "phone_num")
    private String phoneNum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Builder
    public User(String email, String password, String fullName, LocalDate birthDate, String phoneNum, Role role) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.birthDate = birthDate;
        this.phoneNum = phoneNum;
        this.role = role;
    }

    public User update(String fullName) {
        this.fullName = fullName;
        return this;
    }
}