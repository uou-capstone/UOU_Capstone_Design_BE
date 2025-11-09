package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.dto.*;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.user.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
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

    @Transactional(readOnly = true)
    public List<AssessmentSimpleDto> getAssessmentsForCourse(Long courseId) {
        // 1. 과목 정보 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("과목을 찾을 수 없습니다."));

        // 2. 권한 확인 (강의 상세 조회와 동일한 로직)
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        // 2-1. 선생님 권한 확인
        boolean isTeacherOfCourse = teacherRepository.findById(currentUser.getId())
                .map(teacher -> teacher.getId().equals(course.getTeacher().getId()))
                .orElse(false);

        // 2-2. 수강생 권한 확인
        boolean isStudentEnrolled = studentRepository.findById(currentUser.getId())
                .map(student -> enrollmentRepository.existsByStudentAndCourse(student, course))
                .orElse(false);

        // 선생님도 아니고 수강생도 아니면 접근 거부
        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new AccessDeniedException("해당 과목의 평가 목록을 조회할 권한이 없습니다.");
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
                .orElseThrow(() -> new IllegalArgumentException("해당 평가가 없습니다."));

        // 2. 권한 확인 (강의 상세 조회와 동일한 로직)
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        boolean isTeacherOfCourse = teacherRepository.findById(currentUser.getId())
                .map(teacher -> teacher.getId().equals(assessment.getCourse().getTeacher().getId()))
                .orElse(false);

        boolean isStudentEnrolled = studentRepository.findById(currentUser.getId())
                .map(student -> enrollmentRepository.existsByStudentAndCourse(student, assessment.getCourse()))
                .orElse(false);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new AccessDeniedException("해당 평가를 조회할 권한이 없습니다.");
        }

        // 3. Entity를 DTO로 변환하여 반환 (DTO 생성자에서 모든 작업 처리)
        return new AssessmentDetailDto(assessment);
    }
}