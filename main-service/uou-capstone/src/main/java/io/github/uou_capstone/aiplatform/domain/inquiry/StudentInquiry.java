package io.github.uou_capstone.aiplatform.domain.inquiry;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.Student;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "student_inquiries")
public class StudentInquiry extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Lob
    @Column(name = "inquiry_text", nullable = false)
    private String inquiryText; // 학생의 질문

    @Lob
    @Column(name = "agent_answer")
    private String agentAnswer; // AI의 답변

    @Builder
    public StudentInquiry(Student student, Lecture lecture, String inquiryText, String agentAnswer) {
        this.student = student;
        this.lecture = lecture;
        this.inquiryText = inquiryText;
        this.agentAnswer = agentAnswer;
    }
}