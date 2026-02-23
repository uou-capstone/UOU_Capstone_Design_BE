package io.github.uou_capstone.aiplatform.domain.inquiry;

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
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final WebClient aiServiceWebClient;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentInquiryRepository studentInquiryRepository;
    private final GeneratedContentRepository generatedContentRepository;

    @Transactional
    public InquiryResponseDto answerAiQuestion(InquiryRequestDto requestDto) {
        // 1. 학생 정보 조회
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

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

        if (aiResponse == null || aiResponse.getSupplementary() == null) {
            throw new BusinessException(CommonErrorCode.AI_CONTENT_GENERATION_FAILED);
        }

        String answerText = aiResponse.getSupplementary();

        // 5. DB에 학생의 답변 및 AI의 보충 설명 저장 (기록용)
        StudentInquiry inquiry = StudentInquiry.builder()
                .student(student)
                .lecture(lecture)
                .inquiryText(requestDto.getAnswerText()) // 학생의 답변
                .agentAnswer(answerText) // AI의 보충 설명
                // .aiQuestionId(requestDto.getAiQuestionId()) // (필요시 Inquiry 엔티티에도 컬럼 추가)
                .build();
        studentInquiryRepository.save(inquiry);

        // 6. 학생에게 AI의 보충 설명 반환
        return new InquiryResponseDto(answerText);
    }

}
