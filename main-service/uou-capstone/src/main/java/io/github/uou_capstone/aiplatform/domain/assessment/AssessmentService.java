package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.ChoiceOptionCreateDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.QuestionCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    // TeacherRepository, UserRepository 등 권한 확인에 필요한 Repository는 그대로 유지

    @Transactional
    public Assessment createAssessment(Long courseId, AssessmentCreateRequestDto requestDto) {
        // 1. 과목 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 과목입니다."));

        // (권한 확인 로직)

        // 2. Assessment 생성 및 저장
        Assessment newAssessment = Assessment.builder()
                .course(course)
                .title(requestDto.getTitle())
                .type(requestDto.getType())
                .dueDate(requestDto.getDueDate())
                .build();
        assessmentRepository.save(newAssessment);

        // 3. Question 및 ChoiceOption 생성 및 저장
        for (QuestionCreateDto questionDto : requestDto.getQuestions()) {
            Question newQuestion = Question.builder()
                    .assessment(newAssessment)
                    .text(questionDto.getText())
                    .type(questionDto.getType())
                    .createdBy(questionDto.getCreatedBy())
                    .build();
            questionRepository.save(newQuestion);

            if (questionDto.getChoiceOptions() != null) {
                for (ChoiceOptionCreateDto optionDto : questionDto.getChoiceOptions()) {
                    ChoiceOption newOption = ChoiceOption.builder()
                            .question(newQuestion)
                            .text(optionDto.getText())
                            .isCorrect(optionDto.isCorrect())
                            .build();

                    choiceOptionRepository.save(newOption);
                }
            }
        }

        return newAssessment;
    }
}