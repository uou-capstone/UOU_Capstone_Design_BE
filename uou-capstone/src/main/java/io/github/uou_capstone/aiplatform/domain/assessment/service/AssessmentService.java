package io.github.uou_capstone.aiplatform.domain.assessment.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentDetailDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentSimpleDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.ChoiceOptionCreateDto;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.QuestionCreateDto;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final StudentReportAnalysisInvalidationService analysisInvalidationService;

    @Transactional
    public Assessment createAssessment(Long courseId, AssessmentCreateRequestDto requestDto) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        Teacher currentTeacher = currentUserResolver.getTeacher();
        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Assessment newAssessment = Assessment.builder()
                .course(course)
                .title(requestDto.getTitle())
                .type(requestDto.getType())
                .dueDate(requestDto.getDueDate())
                .build();
        assessmentRepository.save(newAssessment);

        for (QuestionCreateDto questionDto : requestDto.getQuestions()) {
            ExamQuestion newQuestion = ExamQuestion.builder()
                    .assessment(newAssessment)
                    .examSession(null)
                    .examType(questionDto.getType())
                    .questionOrder(null)
                    .questionContent(questionDto.getText())
                    .questionMetadata(null)
                    .createdBy(questionDto.getCreatedBy())
                    .build();
            examQuestionRepository.save(newQuestion);

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

        analysisInvalidationService.invalidateCourse(course.getId(), "assessment_created");
        return newAssessment;
    }

    @Transactional(readOnly = true)
    public List<AssessmentSimpleDto> getAssessmentsForCourse(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(course.getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), course);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        return assessmentRepository.findByCourse_Id(courseId).stream()
                .map(AssessmentSimpleDto::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public AssessmentDetailDto getAssessmentDetail(Long assessmentId) {
        Assessment assessment = assessmentRepository.findByIdWithQuestions(assessmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.ASSESSMENT_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(assessment.getCourse().getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), assessment.getCourse());

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        return new AssessmentDetailDto(assessment);
    }
}
