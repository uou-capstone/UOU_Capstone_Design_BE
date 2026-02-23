package io.github.uou_capstone.aiplatform.domain.material.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final LectureRepository lectureRepository;
    private final UserRepository userRepository;
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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. 기존 PDF 자료 삭제
        materialRepository.deleteByLecture_IdAndMaterialType(lectureId, "PDF");

        // 4. 파일을 ai-service의 /api/files/upload 로 포워딩
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", file.getResource());

        AiFileResponseDto aiResponse = aiServiceWebClient.post()
                .uri("/api/files/upload") // ai-service의 파일 업로드 엔드포인트
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(AiFileResponseDto.class)
                .block();

        if (aiResponse == null || aiResponse.getPath() == null) {
            throw new BusinessException(CommonErrorCode.FILE_UPLOAD_FAILED);
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
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));

        if (!material.getLecture().getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. 자료 삭제
        materialRepository.delete(material);
    }
}
