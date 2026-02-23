package io.github.uou_capstone.aiplatform.domain.assessment.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.*;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.AssessmentType;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.ChoiceOption;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.CreatedBy;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.AiGeneratedStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
    private final io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository examQuestionRepository;  // v2: ExamQuestionRepository 사용
    private final ChoiceOptionRepository choiceOptionRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final WebClient aiServiceWebClient;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;

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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        boolean isTeacherOfCourse = teacherRepository.findByUser_Id(currentUser.getId())
                .map(teacher -> teacher.getId().equals(assessment.getCourse().getTeacher().getId()))
                .orElse(false);

        boolean isStudentEnrolled = studentRepository.findById(currentUser.getId())
                .map(student -> enrollmentRepository.existsByStudentAndCourse(student, assessment.getCourse()))
                .orElse(false);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. Entity를 DTO로 변환하여 반환 (DTO 생성자에서 모든 작업 처리)
        return new AssessmentDetailDto(assessment);
    }

    // ✅ 1. AI 퀴즈 생성 요청 메서드
    @Transactional
    public Long generateAiQuiz(Long courseId, Long lectureId) { // 예시: 특정 강의 1개를 기반으로 생성
        // 1. 과목 및 권한 확인
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
        // ... (선생님 권한 확인 로직 추가: createAssessment과 동일) 나중에 추가

        // 2. AI가 참고할 PDF 경로 조회
        Material sourceMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "PDF 자료를 찾을 수 없습니다.")); // PDF 없음
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
                .orElseThrow(() -> new BusinessException(CommonErrorCode.ASSESSMENT_NOT_FOUND)); // 평가 없음

        // 1. 콜백으로 받은 퀴즈 문제와 선택지를 DB에 저장 (v2: ExamQuestion 사용)
        for (QuestionCreateDto questionDto : quizResults) {
                    io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion newQuestion = io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion.builder()
                    .assessment(assessment)
                    .examSession(null)  // v1 호환: examSession은 null
                    .examType(questionDto.getType())  // v2: ExamType 직접 사용
                    .questionOrder(null)
                    .questionContent(questionDto.getText())
                    .questionMetadata(null)
                    .createdBy(CreatedBy.AI) // AI가 생성
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

        // 2. 평가 상태를 '완료'로 변경
        assessment.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
    }
}
