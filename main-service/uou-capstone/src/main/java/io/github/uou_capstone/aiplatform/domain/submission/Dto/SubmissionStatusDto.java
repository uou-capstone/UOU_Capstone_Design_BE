package io.github.uou_capstone.aiplatform.domain.submission.Dto;

import io.github.uou_capstone.aiplatform.domain.submission.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.SubmissionStatus;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class SubmissionStatusDto { //선생님의 제출 현황 조회 목적
    private final Long submissionId;
    private final Long studentId;
    private final String studentName;
    private final LocalDateTime submittedAt;
    private final SubmissionStatus status;
    // 필요하다면 나중에 채점 점수(score) 등을 추가할 수 있습니다.

    public SubmissionStatusDto(Submission submission) {
        this.submissionId = submission.getId();
        this.studentId = submission.getStudent().getId();
        this.studentName = submission.getStudent().getUser().getFullName();
        this.submittedAt = submission.getCreatedAt();
        this.status = submission.getStatus();
    }
}