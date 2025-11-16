package io.github.uou_capstone.aiplatform.domain.assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.dto.*;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.AiGeneratedStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.Material;
import io.github.uou_capstone.aiplatform.domain.material.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentService {

    private final AssessmentRepository assessmentRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final WebClient aiServiceWebClient;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;
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
        boolean isTeacherOfCourse = teacherRepository.findByUser_Id(currentUser.getId())
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

        boolean isTeacherOfCourse = teacherRepository.findByUser_Id(currentUser.getId())
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

    // ✅ 1. AI 퀴즈 생성 요청 메서드
    @Transactional
    public Long generateAiQuiz(Long courseId, Long lectureId) { // 예시: 특정 강의 1개를 기반으로 생성
        // 1. 과목 및 권한 확인
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 과목입니다."));

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 강의입니다."));
        // ... (선생님 권한 확인 로직 추가: createAssessment과 동일) 나중에 추가

        // 2. AI가 참고할 PDF 경로 조회
        Material sourceMaterial = materialRepository.findByLecture_IdAndMaterialType(lectureId, "PDF")
                .orElseThrow(() -> new IllegalArgumentException("AI가 처리할 원본 PDF 자료가 없습니다."));
        String pdfPath = sourceMaterial.getFilePath();

        // 3. 퀴즈 껍데기(Assessment) 먼저 생성
        Assessment assessment = Assessment.builder()
                .course(course)
                .title(lecture.getTitle() + " - AI 생성 퀴즈") // 임시 제목
                .type(AssessmentType.QUIZ)
                .build();

        assessment.updateAiGeneratedStatus(AiGeneratedStatus.PROCESSING);
        assessmentRepository.save(assessment);

        // 4. AI 서비스에 퀴즈 생성 비동기 요청
        AiQuizGenerateRequestDto aiRequest = new AiQuizGenerateRequestDto(assessment.getId(), pdfPath);

        aiServiceWebClient.post()
                .uri("/api/quiz/generate") // ai-service의 퀴즈 생성 엔드포인트 (예시)
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(aiRequest))
                .retrieve()
                .toBodilessEntity()
                .doOnError(error -> {
                    log.error("AI 퀴즈 생성 호출 실패: assessmentId={}", assessment.getId(), error);
                    assessment.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
                    assessmentRepository.save(assessment);
                })
                .subscribe();

        return assessment.getId();
    }

    // AI 퀴즈 콜백 처리 메서드
    @Transactional
    public void saveAiQuizCallback(Long assessmentId, List<QuestionCreateDto> quizResults) {
        Assessment assessment = assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new IllegalArgumentException("콜백: 해당 평가가 없습니다."));

        // 1. 콜백으로 받은 퀴즈 문제와 선택지를 DB에 저장
        for (QuestionCreateDto questionDto : quizResults) {
            Question newQuestion = Question.builder()
                    .assessment(assessment)
                    .text(questionDto.getText())
                    .type(questionDto.getType())
                    .createdBy(CreatedBy.AI) // AI가 생성
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

        // 2. 평가 상태를 '완료'로 변경
        assessment.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
    }
}