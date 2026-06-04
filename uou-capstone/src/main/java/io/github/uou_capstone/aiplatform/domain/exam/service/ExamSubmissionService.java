package io.github.uou_capstone.aiplatform.domain.exam.service;







import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;



import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;



import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.entity.Assessment;

import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;




import io.github.uou_capstone.aiplatform.domain.exam.dto.*;



import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamQuestion;



import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamResult;



import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;



import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;



import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;



import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;



import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamResultRepository;



import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.submission.entity.Submission;

import io.github.uou_capstone.aiplatform.domain.submission.repository.SubmissionRepository;

import io.github.uou_capstone.aiplatform.domain.user.entity.Student;




import io.github.uou_capstone.aiplatform.domain.user.entity.User;



import io.github.uou_capstone.aiplatform.util.AuthorizationUtil;



import lombok.RequiredArgsConstructor;



import lombok.extern.slf4j.Slf4j;



import org.springframework.stereotype.Service;



import org.springframework.transaction.annotation.Transactional;







import java.math.BigDecimal;



import java.time.LocalDateTime;



import java.util.HashMap;



import java.util.List;



import java.util.Map;



import java.util.stream.Collectors;







/**



 * 시험 응시 서비스



 * Version 2의 시험 응시 및 채점을 관리하는 서비스



 * 



 * 주요 기능:



 * 1. 시험 응시: 사용자가 답변을 제출



 * 2. AI 채점: ExamGraderAgent를 통해 답변 채점



 * 3. 결과 저장: ExamResult 엔티티에 결과 저장



 * 4. 피드백 생성: 사용자 피드백 프로필 생성



 */



@Slf4j



@Service



@RequiredArgsConstructor



public class ExamSubmissionService {







    // ========== 의존성 주입 ==========



    private final ExamGradingService examGradingService;  // 채점 서비스



    private final ExamSessionRepository examSessionRepository;



    private final ExamQuestionRepository examQuestionRepository;



    private final ExamResultRepository examResultRepository;



    private final CurrentUserResolver currentUserResolver;



    private final ObjectMapper objectMapper;  // JSON 변환용
    private final TeacherNotificationPublisher teacherNotificationPublisher;


    private final AssessmentRepository assessmentRepository;

    private final SubmissionRepository submissionRepository;






    /**



     * 시험 응시 및 채점



     * 



     * 로직 설명:



     * 1. 권한 확인: 현재 로그인한 사용자가 학생인지 확인



     * 2. 시험 세션 조회: examSessionId로 ExamSession 조회



     * 3. 시험 상태 확인: 시험이 READY 상태인지 확인



     * 4. 문제 조회: 시험 세션에 속한 문제들 조회



     * 5. 답변 검증: 제출된 답변의 개수가 문제 개수와 일치하는지 확인



     * 6. Agent 호출: ExamGraderAgent를 통해 답변 채점



     * 7. 결과 저장: ExamResult 엔티티 생성 및 저장



     * 8. 응답 반환: 채점 결과를 포함한 응답 반환



     * 



     * @param requestDto 시험 응시 요청 DTO (examSessionId, answers)



     * @return 시험 응시 응답 DTO (examResultId, totalScore, gradingDetails)



     */



    @Transactional



    public ExamSubmissionResponseDto submitExam(ExamSubmissionRequestDto requestDto) {



        log.info("시험 응시: examSessionId={}, answerCount={}", 



                requestDto.getExamSessionId(), requestDto.getAnswers().size());







        // ========== 1단계: 권한 확인 ==========



        // 현재 로그인한 사용자 정보 조회



        User currentUser = currentUserResolver.getUser();







        // ========== 권한 확인 ==========



        // 현재 사용자가 학생인지 확인



        AuthorizationUtil.requireStudent(currentUser);







        // ========== 2단계: 시험 세션 조회 ==========



        // examSessionId로 ExamSession 엔티티 조회



        ExamSession examSession = examSessionRepository.findById(requestDto.getExamSessionId())



                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));







        // ========== 3단계: 시험 상태 확인 ==========



        // 시험이 READY 상태인지 확인 (생성 완료된 시험만 응시 가능)



        if (examSession.getStatus() != ExamStatus.READY) {



            throw new BusinessException(



                    CommonErrorCode.INVALID_PHASE, 



                    "시험이 아직 준비되지 않았습니다. 상태: " + examSession.getStatus()



            );



        }







        // ========== 4단계: 문제 조회 ==========



        // 시험 세션에 속한 문제들 조회 (questionOrder 순서대로)



        List<ExamQuestion> questions = examQuestionRepository



                .findByExamSessionIdOrderByQuestionOrder(examSession.getId());



        



        if (questions.isEmpty()) {



            throw new BusinessException(CommonErrorCode.DATA_NOT_FOUND, "시험 문제를 찾을 수 없습니다.");



        }







        // ========== 5단계: 답변 검증 ==========



        // 제출된 답변의 개수가 문제 개수와 일치하는지 확인



        if (requestDto.getAnswers().size() != questions.size()) {



            throw new BusinessException(



                    CommonErrorCode.INVALID_PARAMETER, 



                    String.format("답변 개수가 일치하지 않습니다. 문제: %d개, 답변: %d개", 



                            questions.size(), requestDto.getAnswers().size())



            );



        }







        // ========== 6단계: 답변 데이터 구성 ==========



        // FastAPI 공식 계약(UserAnswer[])에 맞춰 문제별 답변 배열로 변환



        List<Map<String, Object>> userAnswers = new java.util.ArrayList<>();







        for (AnswerSubmissionDto answer : requestDto.getAnswers()) {



            Map<String, Object> answerData = new HashMap<>();



            answerData.put("problem_id", answer.getQuestionId());



            answerData.put("user_response",



                    answer.getSelectedOptionId() != null ? answer.getSelectedOptionId() : answer.getAnswerText());



            if (answer.getAdditionalData() != null && !answer.getAdditionalData().isEmpty()) {



                answerData.put("additional_data", answer.getAdditionalData());



            }



            userAnswers.add(answerData);



        }







        // ========== 7단계: ExamResult 엔티티 생성 ==========



        // ExamResult 엔티티 생성 (아직 채점 전)



        ExamResult examResult = ExamResult.builder()



                .examSession(examSession)



                .submission(null)  // 버전 2에서는 submission은 NULL



                .user(currentUser)



                .build();



        examResult = examResultRepository.save(examResult);







        // ========== 8단계: 채점 및 결과 저장 ==========



        // ExamGradingService를 통해 채점 및 피드백 생성



        GradingResponseDto gradingResult = examGradingService.gradeAndSaveResult(examResult, userAnswers);

        syncAssessmentSubmission(examSession, currentUser, examResult);



        log.info("시험 응시 완료: examResultId={}, totalScore={}/{}", 



                examResult.getId(), gradingResult.getTotalScore(), gradingResult.getMaxScore());







        // 담당 교사 알림: ExamSession.lecture.course.teacher 에게 EXAM_SUBMITTED.
        // 학생이 actor 라 자기 작업 분기는 발동하지 않는다.
        if (examSession.getLecture() != null && examSession.getLecture().getCourse() != null) {
            String displayName = examSession.getDisplayName() != null
                    ? examSession.getDisplayName() : examSession.getExamType().name();
            teacherNotificationPublisher.notifyCourseTeacher(
                    examSession.getLecture().getCourse(),
                    currentUser,
                    NotificationType.EXAM_SUBMITTED,
                    "새 시험 제출",
                    "%s 학생이 '%s' 시험을 응시했습니다. (점수 %s/%s)".formatted(
                            currentUser.getFullName(), displayName,
                            gradingResult.getTotalScore(), gradingResult.getMaxScore()),
                    "EXAM",
                    examSession.getId()
            );
        }

        // ========== 9단계: 응답 반환 ==========



        return ExamSubmissionResponseDto.builder()



                .examResultId(examResult.getId())



                .examSessionId(examSession.getId())



                .totalScore(gradingResult.getTotalScore())



                .maxScore(gradingResult.getMaxScore())



                .overallFeedback(gradingResult.getOverallFeedback())



                .gradingDetails(gradingResult)



                .build();



    }


    private void syncAssessmentSubmission(ExamSession examSession, User currentUser, ExamResult examResult) {
        assessmentRepository.findByExamSession_Id(examSession.getId())
                .ifPresent(assessment -> {
                    Student student = currentUser.getStudent();
                    if (student == null) {
                        throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
                    }

                    Submission submission = submissionRepository.findByStudentAndAssessment(student, assessment)
                            .orElseGet(() -> Submission.builder()
                                    .assessment(assessment)
                                    .student(student)
                                    .build());
                    submission.updateExamResult(examResult);
                    submissionRepository.save(submission);
                });
    }






    /**



     * 시험 결과 조회



     * 



     * 로직 설명:



     * 1. 결과 조회: examResultId로 ExamResult 조회



     * 2. 권한 확인: 현재 사용자가 결과 소유자인지 확인



     * 3. 응답 구성: 결과 정보를 DTO로 변환하여 반환



     * 



     * @param examResultId 시험 결과 ID



     * @return 시험 응시 응답 DTO



     */



    @Transactional(readOnly = true)



    public ExamSubmissionResponseDto getExamResult(Long examResultId) {



        // ========== 1단계: 결과 조회 ==========



        ExamResult examResult = examResultRepository.findById(examResultId)



                .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));







        // ========== 2단계: 권한 확인 ==========



        User currentUser = currentUserResolver.getUser();



        



        if (!examResult.getUser().getId().equals(currentUser.getId())) {



            throw new BusinessException(CommonErrorCode.FORBIDDEN);



        }







        // ========== 3단계: 응답 구성 ==========



        GradingResponseDto gradingDetails = null;



        if (examResult.getUserFeedbackJson() != null) {



            // userFeedbackJson에서 GradingResponseDto 복원



            gradingDetails = objectMapper.convertValue(



                    examResult.getUserFeedbackJson(), 



                    GradingResponseDto.class



            );



        }







        return ExamSubmissionResponseDto.builder()



                .examResultId(examResult.getId())



                .examSessionId(examResult.getExamSession().getId())



                .totalScore(examResult.getTotalScore())



                .maxScore(examResult.getMaxScore())



                .overallFeedback(examResult.getOverallFeedback())



                .gradingDetails(gradingDetails)



                .build();



    }



}



