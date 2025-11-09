package io.github.uou_capstone.aiplatform.domain.submission.Dto;

import io.github.uou_capstone.aiplatform.domain.submission.StudentAnswer;
import io.github.uou_capstone.aiplatform.domain.submission.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.SubmissionStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
public class SubmissionResponseDto {
    private final Long submissionId;
    private final Long assessmentId;
    private final String assessmentTitle;
    private final LocalDateTime submittedAt;
    private final SubmissionStatus status; // 채점 상태 필드 추가
    private final List<StudentAnswerResponseDto> answers;

    // 생성자: Submission 엔티티와 StudentAnswer 엔티티 목록을 받습니다.
    public SubmissionResponseDto(Submission submission, List<StudentAnswer> studentAnswers) {
        this.submissionId = submission.getId();
        this.assessmentId = submission.getAssessment().getId();
        this.assessmentTitle = submission.getAssessment().getTitle();
        this.submittedAt = submission.getCreatedAt(); // BaseTimeEntity의 createdAt 사용
        this.status = submission.getStatus(); //  상태 정보 매핑
        // 각 StudentAnswer를 StudentAnswerResponseDto로 변환하여 리스트로 만듭니다.
        this.answers = studentAnswers.stream()
                .map(StudentAnswerResponseDto::new)
                .collect(Collectors.toList());
    }
}