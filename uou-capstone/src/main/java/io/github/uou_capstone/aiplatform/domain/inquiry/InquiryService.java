package io.github.uou_capstone.aiplatform.domain.inquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.GeneratedContent;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.AiQaResponseDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryResponseDto;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiQaClient;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final FastApiQaClient fastApiQaClient;
    private final CurrentUserResolver currentUserResolver;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentInquiryRepository studentInquiryRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final MaterialRepository materialRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public InquiryResponseDto answerAiQuestion(InquiryRequestDto requestDto) {
        // 1. 학생 정보 조회
        Student student = currentUserResolver.getStudent();

        // 2. 학생이 답변한 '질문 콘텐츠' 정보 조회 (aiQuestionId 사용)
        GeneratedContent questionContent = generatedContentRepository.findByAiQuestionId(requestDto.getAiQuestionId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.AI_CONTENT_NOT_FOUND));

        Lecture lecture = questionContent.getLecture();

        // 3. 권한 확인 (수강생인지)
        if (!enrollmentRepository.existsByStudentAndCourse(student, lecture.getCourse())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 4. ai-service(FastAPI v2.6) 동기 호출: POST /api/v2/qa/evaluate
        Material sourceMaterial = materialRepository
                .findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lecture.getId(), "PDF")
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "PDF 자료를 찾을 수 없습니다."));

        Map<String, Object> qaRequest = Map.of(
                "original_q", questionContent.getContentData(),
                "user_answer", requestDto.getAnswerText(),
                "pdf_path", sourceMaterial.getFilePath()
        );

        Map<String, Object> qaResponse = fastApiQaClient.evaluate(qaRequest);

        AiQaResponseDto aiResponse = objectMapper.convertValue(qaResponse, AiQaResponseDto.class);
        String normalizedStatus = aiResponse.getStatus();
        if (normalizedStatus == null || normalizedStatus.isBlank()) {
            // v2.6 QA 응답(score/feedback/model_answer) 형식과의 호환.
            normalizedStatus = "GOOD";
        }
        String explanation = aiResponse.getExplanation();
        if (explanation == null || explanation.isBlank()) {
            Object modelAnswer = qaResponse.get("model_answer");
            Object feedback = qaResponse.get("feedback");
            explanation = modelAnswer != null ? String.valueOf(modelAnswer)
                    : (feedback != null ? String.valueOf(feedback) : "답변 평가가 완료되었습니다.");
        }

        // 5. DB 저장용 텍스트 가공 (GOOD이면 explanation, BAD면 steps 전체를 JSON 문자열로 저장)
        String agentAnswerToSave;
        if ("GOOD".equals(normalizedStatus)) {
            agentAnswerToSave = explanation;
        } else {
            try {
                agentAnswerToSave = objectMapper.writeValueAsString(aiResponse.getSteps());
            } catch (Exception e) {
                agentAnswerToSave = "하위 개념 학습이 생성되었습니다.";
            }
        }

        StudentInquiry inquiry = StudentInquiry.builder()
                .student(student)
                .lecture(lecture)
                .inquiryText(requestDto.getAnswerText())
                .agentAnswer(agentAnswerToSave)
                .build();
        studentInquiryRepository.save(inquiry);

        // 6. 프론트엔드로 DTO 매핑하여 반환
        return new InquiryResponseDto(
                normalizedStatus,
                explanation,
                aiResponse.getSteps()
        );
    }

}
