package io.github.uou_capstone.aiplatform.domain.user;

import io.github.uou_capstone.aiplatform.domain.inquiry.StudentInquiry;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "lecture", cascade =  CascadeType.ALL, orphanRemoval = true)
    private List<StudentInquiry> studentInquiries = new ArrayList<>();

    @Builder
    public Student(User user, int grade, String classNumber) {
        this.user = user;
        this.grade = grade;
        this.classNumber = classNumber;
    }
}