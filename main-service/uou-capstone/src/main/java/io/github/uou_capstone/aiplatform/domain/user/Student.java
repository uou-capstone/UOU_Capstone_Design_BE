package io.github.uou_capstone.aiplatform.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "students")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "student_id")
    private Long id;

    @Column(nullable = false)
    private int grade;

    @Column(nullable = false)
    private String classNumber;

    @OneToOne
    @MapsId
    @JoinColumn(name = "student_id")
    private User user;
}