//package io.github.uou_capstone.aiplatform.domain.course.lecture.dto;
//
//import lombok.AllArgsConstructor;
//import lombok.Getter;
//
//import java.util.Map;
//
//
///**
// * AI Service (FastAPI)의 /api/delegator/dispatch 엔드포인트로 보낼 요청 DTO
// */
//@Getter
//public class AiRequestDto {
//
//    private final String stage;
//    private final Map<String, Object> payload;
//
//    /**
//     * (1) AI 강의 콘텐츠 생성용 생성자
//     * stage: "generate_script" 또는 "run_all_with_callback"
//     */
//    public AiRequestDto(String stage, Long lectureId, String pdfPath) {
//        this.stage = stage;
//        this.payload = Map.of(
//                "lectureId", lectureId,
//                "pdf_path", pdfPath
//        );
//    }
//
//    /**
//     * ✅ [추가] (2) AI 강의 질문 답변용 생성자
//     * stage: "answer_question"
//     */
//    public AiRequestDto(String stage, Long lectureId, String aiQuestionId, String answer) {
//        this.stage = stage;
//        this.payload = Map.of(
//                "lectureId", lectureId,
//                "questionId", aiQuestionId, // 👈 ai-service의 Pydantic 모델에 맞게 "questionId" 사용
//                "answer", answer
//        );
//    }
//}