package io.github.uou_capstone.aiplatform.domain.material.service;



import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;


import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;


import io.github.uou_capstone.aiplatform.domain.course.entity.Course;


import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;


import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;


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


import lombok.RequiredArgsConstructor;


import org.springframework.http.client.MultipartBodyBuilder;



import org.springframework.stereotype.Service;


import org.springframework.transaction.annotation.Transactional;


import org.springframework.web.multipart.MultipartFile;


import org.springframework.web.reactive.function.BodyInserters;


import org.springframework.web.reactive.function.client.WebClient;


import org.springframework.web.reactive.function.client.WebClientResponseException;




import java.io.IOException;



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



    @Transactional

    public Material uploadFile(Long lectureId, MultipartFile file) throws IOException {

        // 1. 강의 정보 조회

        Lecture lecture = lectureRepository.findById(lectureId)

                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));



        // 2. 권한 확인 (해당 강의의 선생님인지)

        User currentUser = currentUserResolver.getUser();

        Teacher currentTeacher = currentUserResolver.getTeacher();



        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {

            throw new BusinessException(CommonErrorCode.FORBIDDEN);

        }



        // 3. 기존 PDF 자료 삭제

        materialRepository.deleteByLecture_IdAndMaterialType(lectureId, "PDF");



        // 4. 파일을 ai-service의 /api/files/upload 로 포워딩

        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        builder.part("file", file.getResource());



        AiFileResponseDto aiResponse;

        try {

            aiResponse = aiServiceWebClient.post()

                    .uri("/api/files/upload") // ai-service의 파일 업로드 엔드포인트

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



        // 5. DB에 ai-service가 알려준 경로를 저장

        Material material = Material.builder()

                .lecture(lecture)

                .displayName(file.getOriginalFilename())

                .materialType("PDF") // (파일 타입 파싱 로직 추가 가능)

                .filePath(aiResponse.getPath()) // ai-service가 반환한 경로 저장

                .uploadedBy(currentUser.getId())

                .build();



        return materialRepository.save(material);

    }



    @Transactional

    public void deleteMaterial(Long materialId) {

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

        materialRepository.delete(material);

        materialRepository.flush();

    }



    /**

     * PDF 미리보기·채팅용: materialId에 해당하는 파일 바이트를 ai-service에서 가져와 반환.

     * 해당 강의의 선생님 또는 수강생만 접근 가능.

     */

    @Transactional(readOnly = true)

    public byte[] getFileBytes(Long materialId) {

        Material material = materialRepository.findById(materialId)

                .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND));



        User currentUser = currentUserResolver.getUser();



        validateLectureParticipant(material.getLecture(), currentUser);



        String filePath = material.getFilePath();

        if (filePath == null || filePath.isBlank()) {

            throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "파일 경로가 없습니다.");

        }



        try {

            byte[] body = aiServiceWebClient.get()

                    .uri(uriBuilder -> uriBuilder.path("/api/files/serve").queryParam("path", filePath).build())

                    .retrieve()

                    .bodyToMono(byte[].class)

                    .block();



            if (body == null) {

                throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND, "파일을 불러올 수 없습니다.");

            }

            return body;

        } catch (WebClientResponseException e) {

            if (e.getStatusCode().value() == 404) {

                throw new BusinessException(CommonErrorCode.FILE_NOT_FOUND,

                        "파일 서버에서 해당 파일을 찾을 수 없습니다. 파일이 삭제되었거나 서버 경로가 일치하지 않을 수 있습니다.");

            }

            throw e;

        }

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

