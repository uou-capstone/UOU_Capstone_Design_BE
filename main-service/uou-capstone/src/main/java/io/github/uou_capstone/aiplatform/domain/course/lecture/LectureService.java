package io.github.uou_capstone.aiplatform.domain.course.lecture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.*;
import io.github.uou_capstone.aiplatform.domain.course.lecture.exception.StreamingApiException;
import io.github.uou_capstone.aiplatform.domain.material.Material;
import io.github.uou_capstone.aiplatform.domain.material.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final WebClient aiServiceWebClient;
    private final ObjectMapper objectMapper;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @Value("${ai.service.secret-key:}")
    private String aiServiceSecretKey;

    private final CourseRepository courseRepository;
    private final LectureRepository lectureRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final MaterialRepository materialRepository;

    @Transactional
    public Lecture createLecture(Long courseId, LectureCreateRequestDto requestDto) {
        // 1. 강의를 추가할 과목을 DB에서 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("해당 과목이 없습니다."));


        // 2. 권한 확인: 현재 로그인한 사용자가 이 과목의 선생님인지 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new IllegalArgumentException("선생님 계정 정보가 없습니다."));

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 과목에 강의를 생성할 권한이 없습니다.");
        }
        // 3. 새로운 Lecture Entity 생성
        Lecture newLecture = Lecture.builder()
                .course(course)
                .title(requestDto.getTitle())
                .weekNumber(requestDto.getWeekNumber())
                .description(requestDto.getDescription())
                .build();

        // 4. 생성된 Lecture를 DB에 저장하고 반환
        return lectureRepository.save(newLecture);
    }

    @Transactional(readOnly = true)
    public LectureDetailResponseDto getLectureDetail(Long lectureId) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 존재하지 않습니다."));

        // 2. 권한 확인 로직 추가
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));

        Course course = lecture.getCourse(); // 강의가 속한 과목 정보 가져오기

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
            throw new AccessDeniedException("강의를 조회할 권한이 없습니다.");
        }

        // 3. 해당 강의에 속한 AI 생성 콘텐츠 목록 조회
        List<GeneratedContent> contents = generatedContentRepository.findByLectureId(lectureId);

        // 4. DTO로 변환하여 반환
        return new LectureDetailResponseDto(lecture, contents);
    }

    @Transactional
    public Lecture updateLecture(Long lectureId, LectureUpdateRequestDto requestDto) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의가 속한 과목의 선생님인지 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 강의를 수정할 권한이 없습니다.");
        }

        // 3. Entity 업데이트
        lecture.update(requestDto.getTitle(), requestDto.getWeekNumber(), requestDto.getDescription());

        return lecture; // 변경 감지로 인해 save() 호출 불필요
    }

    @Transactional
    public void deleteLecture(Long lectureId) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의가 속한 과목의 선생님인지 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 강의를 삭제할 권한이 없습니다.");
        }

        // 3. 강의 삭제
        lectureRepository.delete(lecture);
    }


    @Transactional
    public void generateAiContent(Long lectureId) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        // 2. 권한 확인
        // Service에서도 이 강의가 '본인'의 과목인지 2차 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 강의의 AI 콘텐츠를 생성할 권한이 없습니다.");
        }

        // 3. AI가 처리할 원본 PDF 경로 조회
        Material sourceMaterial = getLatestPdfMaterial(lectureId);

        String pdfPathToProcess = sourceMaterial.getFilePath();

        // 4. AI 서비스(FastAPI) 비동기 호출
        AiContentGenerateRequestDto aiRequest = new AiContentGenerateRequestDto(lectureId, pdfPathToProcess);

        aiServiceWebClient.post()
                .uri("/api/delegator/dispatch") //  ai-service 엔드포인트
                .contentType(MediaType.APPLICATION_JSON)
                .header("ngrok-skip-browser-warning", "true") // (ngrok 사용 시)
                .body(BodyInserters.fromValue(aiRequest))
                .retrieve()
                .toBodilessEntity() //  성공(200 OK) 여부만 확인
                .doOnError(error -> { //  AI 서비스 호출 실패 시 예외 처리
                    log.error("AI 서비스 호출 실패: lectureId={}", lectureId, error);
                    updateLectureStatusToFailed(lectureId); // 👈 (별도 트랜잭션 메서드)
                })
                .subscribe(); // 비동기 요청 실행 (결과를 기다리지 않음)

        // 5.  강의 상태를 'PROCESSING'(처리 중)으로 변경
        lecture.updateAiGeneratedStatus(AiGeneratedStatus.PROCESSING);
    }


    /**
     * 스트리밍 세션을 초기화한다.
     *  - 강의 소유 선생님 권한을 확인
     *  - 업로드된 PDF 경로를 ai-service에 전달하여 챕터 정보를 생성
     *  - ai-service가 구성한 초기 세션 내용을 DTO로 반환
     */
    @Transactional(readOnly = true)
    public StreamingInitializeResponse initializeLectureStream(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        User currentUser = getCurrentUser();
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 강의의 스트리밍을 초기화할 권한이 없습니다.");
        }

        Material sourceMaterial = getLatestPdfMaterial(lectureId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);
        payload.put("pdf_path", sourceMaterial.getFilePath());
        payload.put("pdfPath", sourceMaterial.getFilePath());

        return executeStreamingStage("initialize", payload, StreamingInitializeResponse.class);
    }


    /**
     * 다음 스트리밍 세그먼트를 요청한다.
     *  - 선생님 또는 수강생 권한 검증 후 ai-service stage(get_next_content)를 호출
     *  - 설명/질문/완료 상태를 StreamingContentResponse로 전달
     */
    @Transactional(readOnly = true)
    public StreamingContentResponse getNextLectureStreamContent(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        User currentUser = getCurrentUser();
        validateLectureParticipant(lecture, currentUser);

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);

        try {
            return executeStreamingStage("get_next_content", payload, StreamingContentResponse.class);
        } catch (StreamingApiException e) {
            // AI 서비스가 "답변 대기 중"이라며 400을 반환한 경우 -> 에러가 아니라 "질문 타임"으로 처리
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST && e.getMessage() != null && e.getMessage().contains("Waiting for answer")) {
                return StreamingContentResponse.builder()
                        .status("WAITING_FOR_ANSWER")
                        .lectureId(lectureId)
                        .waitingForAnswer(true)
                        .hasMore(true)
                        .build();
            }
            throw e;
        }
    }


    /**
     * 현재 스트리밍 세션 상태를 조회한다.
     *  - 선생님 또는 수강생 권한 검증 후 ai-service stage(get_session)를 호출
     *  - 세션에 저장된 메타데이터를 그대로 StreamingSessionDto로 반환
     */
    @Transactional(readOnly = true)
    public StreamingSessionDto getLectureStreamSession(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        User currentUser = getCurrentUser();
        validateLectureParticipant(lecture, currentUser);

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lectureId);
        payload.put("lectureId", lectureId);

        return executeStreamingStage("get_session", payload, StreamingSessionDto.class);
    }

    
    /**
     * 질문 세그먼트에 대한 학생 답변을 ai-service로 전달한다.
     *  - 권한 검증 후 stage(answer_question)를 호출하여 보충 설명을 얻는다.
     */
    @Transactional(readOnly = true)
    public StreamingAnswerResponse answerLectureStreamQuestion(Long lectureId, LectureStreamAnswerRequestDto requestDto) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        User currentUser = getCurrentUser();
        validateLectureParticipant(lecture, currentUser);

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lecture.getId());
        payload.put("lectureId", lecture.getId());
        payload.put("ai_question_id", requestDto.getAiQuestionId());
        payload.put("aiQuestionId", requestDto.getAiQuestionId());
        payload.put("answer", requestDto.getAnswer());

        StreamingAnswerResponse response = executeStreamingStage("answer_question", payload, StreamingAnswerResponse.class);

        if (!"PROCESSING".equals(response.getStatus()) && response.getSupplementary() == null) {
            throw new IllegalStateException("AI 보충 설명 생성에 실패했습니다.");
        }

        return response;
    }

    /**
     * 진행 중인 스트리밍 세션을 강제 종료한다.
     *  - 선생님 혹은 수강생이 모두 취소 요청을 보낼 수 있도록 권한 검증
     *  - ai-service stage(cancel)를 호출해 세션 상태를 cancelled로 전환
     */
    @Transactional(readOnly = true)
    public void cancelLectureStream(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        User currentUser = getCurrentUser();
        validateLectureParticipant(lecture, currentUser);

        Map<String, Object> payload = new HashMap<>();
        payload.put("lecture_id", lecture.getId());
        payload.put("lectureId", lecture.getId());

        executeStreamingStage("cancel", payload);
        log.info("Streaming session cancelled for lectureId={}", lectureId);
    }


    /**
     * AI 작업이 끝난 후 호출될 메서드 (DB 저장)
     * (generateAiContent의 @Transactional과 분리된 새 트랜잭션으로 실행됨)
     */
    @Transactional
    public void saveAiContentCallback(Long lectureId, List<AiResponseDto> aiResults) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("콜백: 해당 강의가 없습니다."));

        // 7. AI 응답 결과를 DB에 저장
        List<GeneratedContent> contentsToSave = aiResults.stream()
                .map(dto -> GeneratedContent.builder()
                        .lecture(lecture)
                        .contentType(ContentType.valueOf(dto.getContentType()))
                        .contentData(dto.getContentData())
                        .materialReferences(dto.getMaterialReferences())
                        .aiQuestionId(dto.getAiQuestionId())
                        .build())
                .collect(Collectors.toList());

        // 8. 강의 상태를 'COMPLETED'로 변경
        if (contentsToSave != null && !contentsToSave.isEmpty()) {
            generatedContentRepository.saveAll(contentsToSave);
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
        } else {
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
        }
    }

    /**
     * AI 작업 실패 시 호출될 메서드 (DB 저장)
     */
    @Transactional
    public void updateLectureStatusToFailed(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId).orElse(null);
        if (lecture != null) {
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
        }
    }

    /**
     *  폴링(Polling)을 위한 상태 조회 메서드
     */
    @Transactional(readOnly = true)
    public String getLectureAiStatus(Long lectureId) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        // (권한 확인 로직 추가 필요 - getLectureDetail과 동일하게)

        return lecture.getAiGeneratedStatus().name();
    }

    /**
     * ai-service 스트리밍 stage 호출 결과를 지정한 타입으로 변환한다.
     */
    private <T> T executeStreamingStage(String stage, Map<String, Object> payload, Class<T> responseType) {
        Map<String, Object> responseMap = executeStreamingStage(stage, payload);
        return objectMapper.convertValue(responseMap, responseType);
    }

    /**
     * ai-service 스트리밍 stage를 공통 WebClient 설정으로 호출한다.
     */
    private Map<String, Object> executeStreamingStage(String stage, Map<String, Object> payload) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("stage", stage);
        requestBody.put("payload", payload);

        Map<String, Object> response = aiServiceWebClient.post()
                .uri("/api/delegator/dispatch")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyCommonHeaders)
                .body(BodyInserters.fromValue(requestBody))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                clientResponse.statusCode(),
                                extractErrorMessage(body, clientResponse.statusCode())))))
                .onStatus(HttpStatusCode::is5xxServerError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new StreamingApiException(
                                clientResponse.statusCode(),
                                extractErrorMessage(body, clientResponse.statusCode())))))
                .bodyToMono(MAP_TYPE)
                .block();

        if (response == null) {
            throw new StreamingApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 서비스 응답이 비어 있습니다.");
        }
        return response;
    }

    /**
     * 모든 스트리밍 요청에 공통으로 적용할 헤더를 설정한다.
     */
    private void applyCommonHeaders(HttpHeaders headers) {
        headers.set("ngrok-skip-browser-warning", "true");
        if (StringUtils.hasText(aiServiceSecretKey)) {
            headers.set("X-AI-SECRET-KEY", aiServiceSecretKey);
        }
    }

    /**
     * ai-service 오류 응답 본문에서 사용자에게 노출할 메시지를 추출한다.
     */
    private String extractErrorMessage(String body, HttpStatusCode statusCode) {
        if (!StringUtils.hasText(body)) {
            return "AI 서비스 호출 중 오류가 발생했습니다. (status=" + statusCode.value() + ")";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("detail")) {
                JsonNode detailNode = node.get("detail");
                if (detailNode.isTextual()) {
                    return detailNode.asText();
                }
                return detailNode.toString();
            }
            if (node.has("message") && node.get("message").isTextual()) {
                return node.get("message").asText();
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse ai-service error response: {}", body, e);
        }
        return body;
    }

    private User getCurrentUser() {
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
    }

    private void validateLectureParticipant(Lecture lecture, User currentUser) {
        Course course = lecture.getCourse();

        boolean isTeacherOfCourse = teacherRepository.findByUser_Id(currentUser.getId())
                .map(teacher -> teacher.getId().equals(course.getTeacher().getId()))
                .orElse(false);

        boolean isStudentEnrolled = studentRepository.findById(currentUser.getId())
                .map(student -> enrollmentRepository.existsByStudentAndCourse(student, course))
                .orElse(false);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new AccessDeniedException("강의를 조회할 권한이 없습니다.");
        }
    }

    private Material getLatestPdfMaterial(Long lectureId) {
        return materialRepository.findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(lectureId, "PDF")
                .orElseThrow(() -> new IllegalArgumentException("AI가 처리할 최신 PDF 자료가 없습니다."));
    }
}