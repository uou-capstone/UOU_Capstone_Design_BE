package io.github.uou_capstone.aiplatform.domain.inquiry;

import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.AiQaRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.AiQaResponseDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryRequestDto;
import io.github.uou_capstone.aiplatform.domain.inquiry.dto.InquiryResponseDto;
import io.github.uou_capstone.aiplatform.domain.material.Material;
import io.github.uou_capstone.aiplatform.domain.material.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.Student;
import io.github.uou_capstone.aiplatform.domain.user.StudentRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final WebClient aiServiceWebClient;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final LectureRepository lectureRepository;
    private final MaterialRepository materialRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentInquiryRepository studentInquiryRepository;

    @Transactional
    public InquiryResponseDto askQuestion(InquiryRequestDto requestDto) {
        // 1. 학생 및 강의 정보 조회
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Student student = studentRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("학생 정보를 찾을 수 없습니다."));
        Lecture lecture = lectureRepository.findById(requestDto.getLectureId())
                .orElseThrow(() -> new IllegalArgumentException("강의를 찾을 수 없습니다."));

        // 2. 권한 확인 (수강생인지)
        if (!enrollmentRepository.existsByStudentAndCourse(student, lecture.getCourse())) {
            throw new AccessDeniedException("수강생만 질문할 수 있습니다.");
        }

        // 3. AI가 참고할 PDF 경로 조회
        Material sourceMaterial = materialRepository.findByLectureIdAndMaterialType(lecture.getId(), "PDF")
                .orElseThrow(() -> new IllegalArgumentException("질문의 기반이 될 PDF 자료가 없습니다."));
        String pdfPath = sourceMaterial.getFilePath(); // ai-service 내부 경로

        // 4. ai-service (FastAPI) 동기 호출
        // (ai-service의 /api/qa/evaluate 모델에 맞게 DTO 생성)
        AiQaRequestDto aiRequest = new AiQaRequestDto(
                requestDto.getQuestionText(), // original_q
                requestDto.getQuestionText(), // user_answer (학생의 질문을 그대로 전달)
                pdfPath
        );

        AiQaResponseDto aiResponse = aiServiceWebClient.post()
                .uri("/api/qa/evaluate") // 👈 ai-service의 Q&A 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .header("ngrok-skip-browser-warning", "true") // (ngrok 사용 시)
                .body(BodyInserters.fromValue(aiRequest))
                .retrieve()
                .bodyToMono(AiQaResponseDto.class)
                .block(); // 👈 학생이 즉시 답변을 기다리므로 동기(.block()) 호출

        if (aiResponse == null || aiResponse.getSupplementaryExplanation() == null) {
            throw new RuntimeException("AI 답변 생성에 실패했습니다.");
        }

        String answerText = aiResponse.getSupplementaryExplanation();

        // 5. DB에 질문과 답변 저장 (기록용)
        StudentInquiry inquiry = StudentInquiry.builder()
                .student(student)
                .lecture(lecture)
                .inquiryText(requestDto.getQuestionText())
                .agentAnswer(answerText)
                .build();

        studentInquiryRepository.save(inquiry);

        // 6. 학생에게 답변 반환
        return new InquiryResponseDto(answerText);
    }
}