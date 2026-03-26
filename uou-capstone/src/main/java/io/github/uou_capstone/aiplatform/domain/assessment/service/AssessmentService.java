package io.github.uou_capstone.aiplatform.domain.assessment.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.*;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository examQuestionRepository;  // v2: ExamQuestionRepository 사용
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional
    public Assessment createAssessment(Long courseId, AssessmentCreateRequestDto requestDto) {
        // 1. 과목 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        // (권한 확인 로직)

        // 2. Assessment 생성 및 저장
        Assessment newAssessment = Assessment.builder()
                .course(course)
                .title(requestDto.getTitle())
                .type(requestDto.getType())
                .dueDate(requestDto.getDueDate())
                .build();
        assessmentRepository.save(newAssessment);

        // 3. ExamQuestion 및 ChoiceOption 생성 및 저장 (v2: ExamQuestion 사용)
        for (QuestionCreateDto questionDto : requestDto.getQuestions()) {
                    io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion newQuestion = io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion.builder()
                    .assessment(newAssessment)
                    .examSession(null)  // v1 호환: examSession은 null
                    .examType(questionDto.getType())  // v2: ExamType 직접 사용
                    .questionOrder(null)  // 순서는 나중에 설정 가능
                    .questionContent(questionDto.getText())
                    .questionMetadata(null)  // v1 호환: metadata는 null
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

        return newAssessment;
    }

    @Transactional(readOnly = true)
    public List<AssessmentSimpleDto> getAssessmentsForCourse(Long courseId) {
        // 1. 과목 정보 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        // 2. 권한 확인 (강의 상세 조회와 동일한 로직)
        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(course.getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), course);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. 해당 과목의 모든 평가 조회
        List<Assessment> assessments = assessmentRepository.findByCourse_Id(courseId);

        // 4. DTO 리스트로 변환하여 반환
        return assessments.stream()
                .map(AssessmentSimpleDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AssessmentDetailDto getAssessmentDetail(Long assessmentId) {
        // 1. Fetch Join을 사용한 최적화된 쿼리
        Assessment assessment = assessmentRepository.findByIdWithQuestions(assessmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.ASSESSMENT_NOT_FOUND));

        // 2. 권한 확인 (강의 상세 조회와 동일한 로직)
        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(assessment.getCourse().getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), assessment.getCourse());

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. Entity를 DTO로 변환하여 반환 (DTO 생성자에서 모든 작업 처리)
        return new AssessmentDetailDto(assessment);
    }

}
