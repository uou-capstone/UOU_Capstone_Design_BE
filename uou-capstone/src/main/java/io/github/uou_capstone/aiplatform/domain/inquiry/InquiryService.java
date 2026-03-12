package io.github.uou_capstone.aiplatform.domain.inquiry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.GeneratedContent;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.AiQuestionAnswerRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.AiQaResponseDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final WebClient aiServiceWebClient;
    private final CurrentUserResolver currentUserResolver;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentInquiryRepository studentInquiryRepository;
    private final GeneratedContentRepository generatedContentRepository;
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

        // 4. ai-service (FastAPI) 동기 호출 (stage = "answer_question")
        AiQuestionAnswerRequestDto aiRequest = new AiQuestionAnswerRequestDto(
                lecture.getId(),
                requestDto.getAiQuestionId(),
                requestDto.getAnswerText()
        );

        AiQaResponseDto aiResponse = aiServiceWebClient.post()
                .uri("/api/delegator/dispatch") // 👈 Delegator의 공통 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .header("ngrok-skip-browser-warning", "true")
                .body(BodyInserters.fromValue(aiRequest))
                .retrieve()
                .bodyToMono(AiQaResponseDto.class)
                .block(); // 👈 즉시 답변을 받아야 하므로 동기(.block()) 호출

        if (aiResponse == null || aiResponse.getStatus() == null) {
            throw new BusinessException(CommonErrorCode.AI_CONTENT_GENERATION_FAILED);
        }

        // 5. DB 저장용 텍스트 가공 (GOOD이면 explanation, BAD면 steps 전체를 JSON 문자열로 저장)
        String agentAnswerToSave;
        if ("GOOD".equals(aiResponse.getStatus())) {
            agentAnswerToSave = aiResponse.getExplanation();
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
                aiResponse.getStatus(),
                aiResponse.getExplanation(),
                aiResponse.getSteps()
        );
    }

}
