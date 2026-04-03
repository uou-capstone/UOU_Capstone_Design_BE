package io.github.uou_capstone.aiplatform.domain.course.lecture.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.*;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.*;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiDelegatorClient;
import io.github.uou_capstone.aiplatform.integration.fastapi.LectureStreamChunk;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1 legacy 강의 AI 흐름 전용 서비스.
 * (delegator/stage 기반 API와 콜백 흐름)
 * 유지보수 전용이며 신규 기능은 이 서비스에 추가하지 않는다.
 * 신규 구현은 v3 learning session 또는 v2 단건 API에만 추가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyLectureFlowService {

    private final FastApiDelegatorClient fastApiDelegatorClient;
    private final ObjectMapper objectMapper;
    private final LectureRepository lectureRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final EnrollmentRepository enrollmentRepository;
    private final MaterialRepository materialRepository;

    @Value("${ai.service.secret-key:}")
    private String aiServiceSecretKey;

    @Transactional
    public void generateAiContent(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        Teacher currentTeacher = currentUserResolver.getTeacher();
        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Material sourceMaterial = getLatestPdfMaterial(lectureId);
        AiContentGenerateRequestDto aiRequest = new AiContentGenerateRequestDto(lectureId, sourceMaterial.getFilePath());

        fastApiDelegatorClient.dispatchGenerateContentAsync(aiRequest, aiServiceSecretKey)
                .doOnError(error -> {
                    log.error("AI 서비스 호출 실패: lectureId={}", lectureId, error);
                    updateLectureStatusToFailed(lectureId);
                })
                .subscribe();

        lecture.updateAiGeneratedStatus(AiGeneratedStatus.PROCESSING);
    }

    @Transactional(readOnly = true)
    public StreamingInitializeResponse initializeLectureStream(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        validateLectureParticipant(lecture.getCourse());

        Material sourceMaterial = getLatestPdfMaterial(lectureId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);
        payload.put("pdf_path", sourceMaterial.getFilePath());
        payload.put("pdfPath", sourceMaterial.getFilePath());

        return executeStreamingStage("initialize", payload, StreamingInitializeResponse.class);
    }

    /**
     * GET /stream/next 용 SSE 스트리밍 버전.
     * DB 준비(강의 조회·권한·자료) 는 동기로 처리하고,
     * FastAPI NDJSON 청크를 reduce 없이 그대로 SSE 이벤트로 방출한다.
     *
     * <p>이벤트 구조:
     * <ul>
     *   <li>event=thought : {"type":"thought_delta","contentType":"THOUGHT","delta":"사고 요약 조각"}</li>
     *   <li>event=message : {"type":"delta","delta":"본문 답변 조각"}</li>
     *   <li>event=done    : {"type":"done","lectureId":N,"hasMore":false,"waitingForAnswer":false}</li>
     *   <li>event=done    : {"type":"done","status":"WAITING_FOR_ANSWER","waitingForAnswer":true,...}</li>
     *   <li>event=error   : {"type":"error","message":"..."}</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Flux<ServerSentEvent<Map<String, Object>>> streamNextContent(Long lectureId, Integer pageNumber, String userMessage, Long materialId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        validateLectureParticipant(lecture.getCourse());

        String pdfPath;
        if (materialId != null) {
            pdfPath = materialRepository.findById(materialId)
                    .map(Material::getFilePath)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND));
        } else {
            pdfPath = getLatestPdfMaterial(lectureId).getFilePath();
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);
        payload.put("pdf_path", pdfPath);
        if (pageNumber != null && pageNumber > 0) {
            payload.put("page_number", pageNumber);
            payload.put("pageNumber", pageNumber);
        }
        if (userMessage != null && !userMessage.isBlank()) {
            payload.put("user_message", userMessage);
            payload.put("userMessage", userMessage);
        }

        Map<String, Object> doneData = new HashMap<>();
        doneData.put("type", "done");
        doneData.put("lectureId", lectureId);
        doneData.put("hasMore", false);
        doneData.put("waitingForAnswer", false);
        doneData.put("chapterTitle", "페이지 설명");

        return fastApiDelegatorClient.streamLectureContent(payload, aiServiceSecretKey)
                .map(chunk -> {
                    Map<String, Object> data = new HashMap<>();
                    if (chunk.kind() == LectureStreamChunk.Kind.THOUGHT) {
                        data.put("type", "thought_delta");
                        data.put("contentType", "THOUGHT");
                        data.put("delta", chunk.delta());
                        return ServerSentEvent.<Map<String, Object>>builder()
                                .event("thought")
                                .data(data)
                                .build();
                    }
                    data.put("type", "delta");
                    data.put("delta", chunk.delta());
                    return ServerSentEvent.<Map<String, Object>>builder()
                            .event("message")
                            .data(data)
                            .build();
                })
                .concatWith(Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                        .event("done")
                        .data(doneData)
                        .build()))
                .onErrorResume(e -> {
                    if (e instanceof StreamingApiException sae
                            && sae.getStatusCode() == HttpStatus.BAD_REQUEST
                            && sae.getMessage() != null
                            && sae.getMessage().contains("Waiting for answer")) {
                        String aiQuestionId = extractQuestionIdFromMessage(sae.getMessage());
                        Map<String, Object> waitData = new HashMap<>();
                        waitData.put("type", "done");
                        waitData.put("status", "WAITING_FOR_ANSWER");
                        waitData.put("lectureId", lectureId);
                        waitData.put("waitingForAnswer", true);
                        waitData.put("hasMore", true);
                        waitData.put("aiQuestionId", aiQuestionId);
                        return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                                .event("done")
                                .data(waitData)
                                .build());
                    }
                    Map<String, Object> errorData = new HashMap<>();
                    errorData.put("type", "error");
                    errorData.put("message", e.getMessage());
                    return Flux.just(ServerSentEvent.<Map<String, Object>>builder()
                            .event("error")
                            .data(errorData)
                            .build());
                });
    }

    @Transactional(readOnly = true)
    public StreamingSessionDto getLectureStreamSession(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        validateLectureParticipant(lecture.getCourse());

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);

        return executeStreamingStage("get_session", payload, StreamingSessionDto.class);
    }

    @Transactional(readOnly = true)
    public StreamingAnswerResponse answerLectureStreamQuestion(Long lectureId, LectureStreamAnswerRequestDto requestDto) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        validateLectureParticipant(lecture.getCourse());

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lecture.getId());
        payload.put("lectureId", lecture.getId());
        payload.put("ai_question_id", requestDto.getAiQuestionId());
        payload.put("aiQuestionId", requestDto.getAiQuestionId());
        payload.put("answer", requestDto.getAnswer());

        try {
            StreamingAnswerResponse response = executeStreamingStage("answer_question", payload, StreamingAnswerResponse.class);
            if (!"PROCESSING".equals(response.getStatus()) && response.getSupplementary() == null) {
                throw new BusinessException(CommonErrorCode.AI_CONTENT_GENERATION_FAILED);
            }
            return response;
        } catch (StreamingApiException e) {
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST && e.getMessage() != null
                    && e.getMessage().contains("Not waiting for answer")) {
                log.debug("answer_question 400 treated as already answered: {}", e.getMessage());
                return StreamingAnswerResponse.builder()
                        .status("ALREADY_ANSWERED")
                        .lectureId(lectureId)
                        .aiQuestionId(requestDto.getAiQuestionId())
                        .supplementary("이미 답변이 처리되었습니다. 다음으로 진행해 주세요.")
                        .canContinue(true)
                        .build();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public void cancelLectureStream(Long lectureId) {
        Lecture lecture = lectureRepository.findByIdWithCourse(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        validateLectureParticipant(lecture.getCourse());

        Material sourceMaterial = getLatestPdfMaterial(lectureId);
        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lecture.getId());
        payload.put("lectureId", lecture.getId());
        payload.put("pdf_path", sourceMaterial.getFilePath());
        payload.put("pdfPath", sourceMaterial.getFilePath());

        executeStreamingStage("cancel", payload);
        log.info("Streaming session cancelled for lectureId={}", lectureId);
    }

    @Transactional
    public void saveAiContentCallback(Long lectureId, List<AiResponseDto> aiResults) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        List<GeneratedContent> contentsToSave = aiResults.stream()
                .map(dto -> GeneratedContent.builder()
                        .lecture(lecture)
                        .contentType(ContentType.valueOf(dto.getContentType()))
                        .contentData(dto.getContentData())
                        .materialReferences(dto.getMaterialReferences())
                        .aiQuestionId(dto.getAiQuestionId())
                        .build())
                .collect(Collectors.toList());

        if (contentsToSave != null && !contentsToSave.isEmpty()) {
            generatedContentRepository.saveAll(contentsToSave);
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
        } else {
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
        }
    }

    @Transactional
    public void updateLectureStatusToFailed(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
        if (lecture != null) {
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
        }
    }

    @Transactional(readOnly = true)
    public String getLectureAiStatus(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
        return lecture.getAiGeneratedStatus().name();
    }

    private <T> T executeStreamingStage(String stage, Map<String, Object> payload, Class<T> responseType) {
        Map<String, Object> responseMap = executeStreamingStage(stage, payload);
        return objectMapper.convertValue(responseMap, responseType);
    }

    private Map<String, Object> executeStreamingStage(String stage, Map<String, Object> payload) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("stage", stage);
        requestBody.put("payload", payload);
        return fastApiDelegatorClient.dispatchStage(requestBody, aiServiceSecretKey);
    }

    private String extractQuestionIdFromMessage(String message) {
        try {
            int start = message.indexOf("question ");
            if (start != -1) {
                String sub = message.substring(start + 9);
                int end = sub.indexOf(".");
                if (end == -1) end = sub.length();
                return sub.substring(0, end).trim();
            }
        } catch (Exception e) {
            log.warn("질문 ID 파싱 실패: {}", message);
        }
        return null;
    }

    private void validateLectureParticipant(Course course) {
        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(course.getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), course);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private Material getLatestPdfMaterial(Long lectureId) {
        return materialRepository.findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "AI 처리에 필요한 PDF 자료를 찾을 수 없습니다."));
    }
}

