package io.github.uou_capstone.aiplatform.domain.material.service;



import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;


import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;


import io.github.uou_capstone.aiplatform.domain.course.entity.Course;


import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;


import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;


import io.github.uou_capstone.aiplatform.domain.learning.service.LearningChatPersistenceService;


import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;


import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;


import io.github.uou_capstone.aiplatform.domain.material.entity.Material;


import io.github.uou_capstone.aiplatform.domain.material.dto.AiFileResponseDto;


import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;


import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;


import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;


import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;


import io.github.uou_capstone.aiplatform.domain.user.entity.User;


import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;


import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;



import org.springframework.stereotype.Service;


import org.springframework.transaction.support.TransactionOperations;


import org.springframework.web.multipart.MultipartFile;


import org.springframework.web.reactive.function.BodyInserters;


import org.springframework.web.reactive.function.client.WebClient;


import org.springframework.web.reactive.function.client.WebClientResponseException;




import java.io.IOException;



@Slf4j
@Service

@RequiredArgsConstructor

public class MaterialService {



    private final MaterialRepository materialRepository;

    private final LectureRepository lectureRepository;

    private final UserRepository userRepository;

    private final CurrentUserResolver currentUserResolver;

    private final TeacherRepository teacherRepository;

    private final WebClient aiServiceWebClient;

    private final StudentRepository studentRepository;

    private final EnrollmentRepository enrollmentRepository;

    private final CourseRepository courseRepository;

    private final FastApiSessionClient fastApiSessionClient;

    private final LearningChatPersistenceService learningChatPersistenceService;

    private final TransactionOperations transactionOperations;



    public Material uploadFile(Long lectureId, MultipartFile file) throws IOException {

        // 1. 강의 정보 조회 + 권한 확인 (트랜잭션 밖에서 읽기만)

        Lecture lecture = lectureRepository.findById(lectureId)

                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        Teacher currentTeacher = currentUserResolver.getTeacher();

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {

            throw new BusinessException(CommonErrorCode.FORBIDDEN);

        }

        // 2. 파일을 ai-service로 포워딩 (외부 HTTP — 트랜잭션 밖에서 수행)

        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        builder.part("file", file.getResource());

        AiFileResponseDto aiResponse;

        try {

            aiResponse = aiServiceWebClient.post()

                    .uri("/api/files/upload")

                    .body(BodyInserters.fromMultipartData(builder.build()))

                    .retrieve()

                    .bodyToMono(AiFileResponseDto.class)

                    .block();

        } catch (WebClientResponseException e) {

            if (e.getStatusCode().value() == 404) {

                throw new BusinessException(CommonErrorCode.FILE_UPLOAD_FAILED,

                        "파일 서버 업로드 API를 찾을 수 없습니다. ai-service에 /api/files/upload 엔드포인트가 있는지 확인해주세요.");

            }

            throw e;

        }

        if (aiResponse == null || aiResponse.getPath() == null) {

            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_FAILED,

                    "파일 서버가 저장 경로를 반환하지 않았습니다.");

        }

        // 3. DB 쓰기만 트랜잭션 안에서 수행

        Material material = transactionOperations.execute(status ->
                saveUploadedMaterial(lectureId, lecture, currentUser, file.getOriginalFilename(), aiResponse.getPath()));
        cleanupLearningSessionAfterMaterialChange(lectureId);
        return material;

    }

    private Material saveUploadedMaterial(Long lectureId, Lecture lecture, User uploader,
                                          String displayName, String filePath) {
        materialRepository.deleteByLecture_IdAndMaterialType(lectureId, "PDF");

        Material material = Material.builder()
                .lecture(lecture)
                .displayName(displayName)
                .materialType("PDF")
                .filePath(filePath)
                .uploadedBy(uploader.getId())
                .build();

        return materialRepository.save(material);
    }



    public void deleteMaterial(Long materialId) {
        Long lectureId = transactionOperations.execute(status -> deleteMaterialInTransaction(materialId));
        cleanupLearningSessionAfterMaterialChange(lectureId);
    }

    private Long deleteMaterialInTransaction(Long materialId) {

        // 1. 자료 조회

        Material material = materialRepository.findById(materialId)

                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND));



        // 2. 권한 확인 (해당 강의의 선생님인지)

        User currentUser = currentUserResolver.getUser();

        Teacher currentTeacher = currentUserResolver.getTeacher();



        if (!material.getLecture().getCourse().getTeacher().getId().equals(currentTeacher.getId())) {

            throw new BusinessException(CommonErrorCode.FORBIDDEN);

        }



        // 3. 자료 삭제 (flush로 즉시 DB 반영, 이후 contents 조회에서 제외 보장)

        Long lectureId = material.getLecture().getId();

        materialRepository.delete(material);

        materialRepository.flush();

        return lectureId;

    }

    private void cleanupLearningSessionAfterMaterialChange(Long lectureId) {
        try {
            fastApiSessionClient.invalidateByLecture(lectureId).block();
            log.info("FastAPI learning session invalidated after material change: lectureId={}", lectureId);
        } catch (Exception e) {
            log.warn("FastAPI learning session invalidate failed after material change: lectureId={}, err={}",
                    lectureId, e.getMessage());
        }

        try {
            int ended = learningChatPersistenceService.endActiveSessionsByLecture(lectureId);
            if (ended > 0) {
                log.info("Spring learning chat sessions ended after material change: lectureId={}, count={}",
                        lectureId, ended);
            }
        } catch (Exception e) {
            log.warn("Spring learning chat session ending failed after material change: lectureId={}, err={}",
                    lectureId, e.getMessage());
        }
    }



    /**

     * PDF 미리보기·채팅용: materialId에 해당하는 파일 바이트를 ai-service에서 가져와 반환.

     * 해당 강의의 선생님 또는 수강생만 접근 가능.

     */

    // 트랜잭션 불필요: DB 읽기 후 WebClient 스트리밍은 MVC async 스레드에서 수행되므로
    // @Transactional(readOnly=true)가 걸려 있으면 커넥션을 스트리밍 내내 점유한다.
    public StreamingResponseBody streamFile(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();
        validateLectureParticipant(material.getLecture(), currentUser);

        String filePath = material.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "파일 경로가 없습니다.");
        }

        // 100MB 파일을 byte[]로 힙에 올리지 않고 업스트림 WebClient 응답을 청크 단위로 바로 OutputStream에 흘려보낸다.
        // StreamingResponseBody의 writeTo는 컨트롤러가 반환한 후 별도 MVC async 스레드에서 호출된다.
        return out -> {
            Flux<DataBuffer> body = aiServiceWebClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/files/serve").queryParam("path", filePath).build())
                    .retrieve()
                    .onStatus(status -> status.value() == 404,
                            response -> Mono.error(new BusinessException(CommonErrorCode.FILE_NOT_FOUND,
                                    "파일 서버에서 해당 파일을 찾을 수 없습니다. 파일이 삭제되었거나 서버 경로가 일치하지 않을 수 있습니다.")))
                    .bodyToFlux(DataBuffer.class);

            try {
                DataBufferUtils.write(body, out)
                        .map(DataBufferUtils::release)
                        .then()
                        .block();
            } catch (Exception e) {
                log.warn("파일 스트리밍 실패: materialId={}, path={}, err={}", materialId, filePath, e.getMessage());
                throw new IOException("파일 스트리밍 중 오류가 발생했습니다.", e);
            }
        };
    }



    private void validateLectureParticipant(Lecture lecture, User currentUser) {

        Course course = lecture.getCourse();

        boolean isTeacher = teacherRepository.findByUser_Id(currentUser.getId())

                .map(t -> t.getId().equals(course.getTeacher().getId()))

                .orElse(false);

        boolean isEnrolled = studentRepository.findByUser_Id(currentUser.getId())

                .map(student -> enrollmentRepository.existsByStudentAndCourse(student, course))

                .orElse(false);

        if (!isTeacher && !isEnrolled) {

            throw new BusinessException(CommonErrorCode.FORBIDDEN);

        }

    }

}

