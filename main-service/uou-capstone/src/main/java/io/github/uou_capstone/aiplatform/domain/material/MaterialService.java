package io.github.uou_capstone.aiplatform.domain.material;

import io.github.uou_capstone.aiplatform.domain.course.lecture.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.material.dto.AiFileResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.security.access.AccessDeniedException;
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


    @Transactional
    public Material uploadFile(Long lectureId, MultipartFile file) throws IOException {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("해당 강의가 없습니다."));

        // 2. 권한 확인 (해당 강의의 선생님인지)
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자 정보를 찾을 수 없습니다."));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("파일을 업로드할 권한이 없습니다.");
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
            throw new RuntimeException("AI 서비스 파일 업로드 실패");
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
}