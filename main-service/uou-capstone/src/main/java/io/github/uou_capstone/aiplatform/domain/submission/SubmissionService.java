package io.github.uou_capstone.aiplatform.domain.submission;

import io.github.uou_capstone.aiplatform.domain.assessment.*;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.submission.Dto.StudentAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.submission.Dto.SubmissionRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.Student;
import io.github.uou_capstone.aiplatform.domain.user.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final StudentAnswerRepository studentAnswerRepository;
    private final StudentRepository studentRepository;
    private final AssessmentRepository assessmentRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceOptionRepository choiceOptionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    @Transactional
    public Long createSubmission(Long assessmentId, SubmissionRequestDto requestDto) {
// 1. 현재 로그인한 사용자 이메일 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();

        // 2. 이메일로 User 엔티티 조회
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        // 3. User ID를 사용하여 Student 엔티티 조회
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("학생 정보를 찾을 수 없습니다."));
        // 2. 평가 정보 가져오기
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new IllegalArgumentException("평가를 찾을 수 없습니다."));

        // 3. 비즈니스 로직 검증
        // 3-1. 해당 과목의 수강생이 맞는지 확인
        if (!enrollmentRepository.existsByStudentAndCourse(student, assessment.getCourse())) {
            throw new IllegalStateException("해당 과목의 수강생이 아닙니다.");
        }
        // 3-2. 이미 제출한 평가인지 확인
        if (submissionRepository.existsByStudentAndAssessment(student, assessment)) {
            throw new IllegalStateException("이미 답안을 제출한 평가입니다.");
        }

        // 4. Submission(제출 기록) 생성 및 저장
        Submission submission = Submission.builder()
                .assessment(assessment)
                .student(student)
                .build();
        submissionRepository.save(submission);

        // 5. 개별 답안(StudentAnswer)들 저장
        for (StudentAnswerRequestDto answerDto : requestDto.getAnswers()) {
            Question question = questionRepository.findById(answerDto.getQuestionId())
                    .orElseThrow(() -> new IllegalArgumentException("문제를 찾을 수 없습니다."));

            ChoiceOption choiceOption = null;
            if (answerDto.getChoiceOptionId() != null) {
                choiceOption = choiceOptionRepository.findById(answerDto.getChoiceOptionId())
                        .orElseThrow(() -> new IllegalArgumentException("선택지를 찾을 수 없습니다."));
            }

            StudentAnswer studentAnswer = StudentAnswer.builder()
                    .submission(submission)
                    .question(question)
                    .choiceOption(choiceOption)
                    .descriptiveAnswer(answerDto.getDescriptiveAnswer())
                    .build();
            studentAnswerRepository.save(studentAnswer);
        }
        return submission.getId();
    }
}