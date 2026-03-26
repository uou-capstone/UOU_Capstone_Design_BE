package io.github.uou_capstone.aiplatform.domain.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        // 4. 평가 기준 PDF 결정 (시험 materialId와 동일하게, 다중 PDF 시 명시 권장)
        Material sourceMaterial = resolveQaPdfMaterial(lecture, requestDto.getMaterialId(), questionContent.getMaterialReferences());

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

    /**
     * QA에 사용할 PDF 자료를 결정한다.
     * <ol>
     *   <li>요청 {@code materialId}가 있으면 해당 자료(같은 강의·PDF 타입) 사용</li>
     *   <li>없으면 질문 콘텐츠 {@code materialReferences}에서 material ID 파싱 시도</li>
     *   <li>그래도 없으면 강의당 PDF가 정확히 1개일 때만 자동 선택</li>
     *   <li>PDF가 2개 이상이면 {@code materialId} 필수</li>
     * </ol>
     */
    private Material resolveQaPdfMaterial(Lecture lecture, Long requestMaterialId, String materialReferencesJson) {
        Long lectureId = lecture.getId();

        if (requestMaterialId != null) {
            return loadPdfMaterialForLecture(lectureId, requestMaterialId);
        }

        Long fromRefs = tryParseMaterialIdFromReferences(materialReferencesJson);
        if (fromRefs != null) {
            return loadPdfMaterialForLecture(lectureId, fromRefs);
        }

        List<Material> pdfs = materialRepository.findByLecture_IdOrderByCreatedAtDesc(lectureId).stream()
                .filter(m -> "PDF".equals(m.getMaterialType()))
                .collect(Collectors.toList());

        if (pdfs.isEmpty()) {
            throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "PDF 자료를 찾을 수 없습니다.");
        }
        if (pdfs.size() > 1) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "강의에 PDF가 여러 개입니다. 답변 요청에 materialId를 지정해 주세요.");
        }
        return pdfs.get(0);
    }

    private Material loadPdfMaterialForLecture(Long lectureId, Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "요청한 자료를 찾을 수 없습니다."));
        if (!material.getLecture().getId().equals(lectureId)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "materialId가 해당 강의와 일치하지 않습니다.");
        }
        if (!"PDF".equals(material.getMaterialType())) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "QA 평가는 PDF 자료(materialId)만 지원합니다.");
        }
        return material;
    }

    private Long tryParseMaterialIdFromReferences(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String json = raw.trim();
        try {
            if (json.startsWith("{")) {
                JsonNode n = objectMapper.readTree(json);
                if (n.hasNonNull("materialId")) {
                    return n.get("materialId").asLong();
                }
                if (n.hasNonNull("material_id")) {
                    return n.get("material_id").asLong();
                }
            } else {
                return Long.parseLong(json);
            }
        } catch (Exception e) {
            log.debug("materialReferences에서 materialId 파싱 실패: {}", raw, e);
        }
        return null;
    }

}
