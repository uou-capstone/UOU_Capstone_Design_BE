package io.github.uou_capstone.aiplatform.domain.user;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "teachers")
public class Teacher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "teacher_id")
    private Long id;

    @Column(nullable = false)
    private String schoolName;

    @Column(nullable = false)
    private String department;

    @OneToOne
    @MapsId
    @JoinColumn(name = "teacher_id")
    private User user;
}