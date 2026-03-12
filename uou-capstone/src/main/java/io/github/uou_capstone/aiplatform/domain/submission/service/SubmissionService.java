package io.github.uou_capstone.aiplatform.domain.submission.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.submission.dto.StudentAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.submission.dto.SubmissionRequestDto;
import io.github.uou_capstone.aiplatform.domain.submission.dto.SubmissionResponseDto;
import io.github.uou_capstone.aiplatform.domain.submission.dto.SubmissionStatusDto;
import io.github.uou_capstone.aiplatform.domain.submission.entity.StudentAnswer;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;
import io.github.uou_capstone.aiplatform.domain.submission.repository.StudentAnswerRepository;
import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final StudentAnswerRepository studentAnswerRepository;
    private final AssessmentRepository assessmentRepository;
    private final io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository examQuestionRepository;  // v2: ExamQuestionRepository 사용
    private final ChoiceOptionRepository choiceOptionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional
    public Long createSubmission(Long assessmentId, SubmissionRequestDto requestDto) {
        // 1. 현재 학생 정보 가져오기
        Student student = currentUserResolver.getStudent();

        // 2. 평가 정보 가져오기 (변경 없음)
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.ASSESSMENT_NOT_FOUND));

        // 3. 비즈니스 로직 검증 (변경 없음)
        if (!enrollmentRepository.existsByStudentAndCourse(student, assessment.getCourse())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (submissionRepository.existsByStudentAndAssessment(student, assessment)) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE);
        }

        // 4. Submission 생성 및 저장 (변경 없음)
        Submission submission = Submission.builder()
                .assessment(assessment)
                .student(student)
                .build();
        submissionRepository.save(submission);

        // 5. 개별 StudentAnswer 저장 (v2: ExamQuestion 사용)
        for (StudentAnswerRequestDto answerDto : requestDto.getAnswers()) {
                    io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion question = examQuestionRepository.findById(answerDto.getQuestionId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.QUESTION_NOT_FOUND, "해당 문제를 찾을 수 없습니다. ID: " + answerDto.getQuestionId()));

            ChoiceOption choiceOption = null;
            // DTO의 choiceOptionId 사용
            if (answerDto.getChoiceOptionId() != null) {
                choiceOption = choiceOptionRepository.findById(answerDto.getChoiceOptionId())
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.OPTION_NOT_FOUND, "해당 선택지를 찾을 수 없습니다. ID: " + answerDto.getChoiceOptionId()));
            }

            StudentAnswer studentAnswer = StudentAnswer.builder()
                    .submission(submission)
                    .question(question)
                    .choiceOption(choiceOption) // 찾은 ChoiceOption 객체 전달
                    .descriptiveAnswer(answerDto.getDescriptiveAnswer())
                    .build();
            studentAnswerRepository.save(studentAnswer);
        }
        return submission.getId();
    }

    @Transactional(readOnly = true)
    public SubmissionResponseDto getSubmissionResult(Long submissionId) {
        // 1. 제출 기록 찾기 (변경 없음)
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.SUBMISSION_NOT_FOUND));

        // 2. 현재 사용자 정보 가져오기
        User user = currentUserResolver.getUser();

        // 3. 소유권 확인 (변경 없음)
        if (!submission.getStudent().getUser().getId().equals(user.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 4. 해당 제출의 모든 답안 찾기 (변경 없음)
        List<StudentAnswer> studentAnswers = studentAnswerRepository.findBySubmissionId(submissionId);

        // 5. 업데이트된 DTO 생성자를 사용하여 DTO로 변환 (여기 코드 변경 필요 없음)
        return new SubmissionResponseDto(submission, studentAnswers);
    }


    @Transactional(readOnly = true)
    public List<SubmissionStatusDto> getSubmissionsForAssessment(Long assessmentId) {
        // 1. 평가 정보 조회 (권한 확인을 위해 필요)
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.ASSESSMENT_NOT_FOUND));

        // 2. 선생님 권한 인증
        Teacher currentTeacher = currentUserResolver.getTeacher();
        if (!assessment.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        // 3. 해당 평가에 대한 모든 제출 기록 조회
        List<Submission> submissions = submissionRepository.findByAssessmentId(assessmentId);

        // 4. DTO 리스트로 변환하여 반환
        return submissions.stream()
                .map(SubmissionStatusDto::new)
                .collect(Collectors.toList());
    }
}
