package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.*;
import io.github.uou_capstone.aiplatform.domain.material.Material;
import io.github.uou_capstone.aiplatform.domain.material.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final WebClient aiServiceWebClient;

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
        Teacher currentTeacher = teacherRepository.findById(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

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
        boolean isTeacherOfCourse = teacherRepository.findById(currentUser.getId())
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
        Teacher currentTeacher = teacherRepository.findById(currentUser.getId())
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
        Teacher currentTeacher = teacherRepository.findById(currentUser.getId())
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
        Teacher currentTeacher = teacherRepository.findById(currentUser.getId())
                .orElseThrow(() -> new AccessDeniedException("선생님 계정 정보가 없습니다."));

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new AccessDeniedException("해당 강의의 AI 콘텐츠를 생성할 권한이 없습니다.");
        }

        // 3. AI가 처리할 원본 PDF 경로 조회
        Material sourceMaterial = materialRepository.findByLecture_IdAndMaterialType(lectureId, "PDF")
                .orElseThrow(() -> new IllegalArgumentException("AI가 처리할 원본 PDF 자료가 없습니다."));

        String pdfPathToProcess = sourceMaterial.getFilePath();

        //4. AI 서비스(FastAPI) 비동기 호출
        AiRequestDto aiRequest = new AiRequestDto(lectureId, pdfPathToProcess);

        aiServiceWebClient.post()
                .uri("/api/delegator/dispatch")
                .contentType(MediaType.APPLICATION_JSON) // Content-Type을 JSON으로 명시
                .header("ngrok-skip-browser-warning", "true") //Ngrok 경고 스킵 헤더
                .body(BodyInserters.fromValue(aiRequest))
                .retrieve()
                .toBodilessEntity()
                .doOnError(error -> {
                    log.error("AI 서비스 호출 실패: lectureId={}", lectureId, error);
                    lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
                    lectureRepository.save(lecture);
                })
                .subscribe();

        // 5.  강의 상태를 'PROCESSING'(처리 중)으로 변경
        lecture.updateAiGeneratedStatus(AiGeneratedStatus.PROCESSING);
        // (DB 저장은 @Transactional이 알아서 처리)
//        // 4. AI 서비스(FastAPI) 동기 호출
//        AiRequestDto aiRequest = new AiRequestDto(pdfPathToProcess); // AiRequestDto(lectureId, pdfPath)로 수정 필요
//
//        // ✅ [수정] WebClient 호출을 동기(.block())로 변경
//        AiApiResponseWrapper apiResponse = aiServiceWebClient.post()
//                .uri("/api/delegator/dispatch") // 👈 ai-service 엔드포인트
//                .contentType(MediaType.APPLICATION_JSON)
//                .header("ngrok-skip-browser-warning", "true")
//                .body(BodyInserters.fromValue(aiRequest))
//                .retrieve()
//                .bodyToMono(AiApiResponseWrapper.class) // 👈 껍데기 DTO로 응답을 받음
//                .block(); // 👈 AI 서비스가 응답을 줄 때까지 (최대 5분) 동기식으로 기다림
//
//        // 5. ✅ [수정] 껍데기 DTO에서 실제 결과 리스트 추출
//        List<AiResponseDto> aiResults;
//        if (apiResponse != null && "ok".equals(apiResponse.getStatus())) {
//            aiResults = apiResponse.getResults();
//        } else {
//            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
//            throw new RuntimeException("AI 서비스 호출에 실패했거나 'ok' 상태가 아닙니다.");
//        }
//
//        // 6. AI 응답 결과를 DB에 저장
//        List<GeneratedContent> contentsToSave = aiResults.stream()
//                .map(dto -> GeneratedContent.builder()
//                        .lecture(lecture)
//                        .contentType(ContentType.valueOf(dto.getContentType()))
//                        .contentData(dto.getContentData())
//                        .materialReferences(dto.getMaterialReferences())
//                        .build())
//                .collect(Collectors.toList());
//
//        // 7. 강의 상태를 '완료'로 변경
//        if (contentsToSave != null && !contentsToSave.isEmpty()) {
//            generatedContentRepository.saveAll(contentsToSave);
//            lecture.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
//        } else {
//            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
//        }
    }

    @Transactional
    public void saveAiContentCallback(Long lectureId, List<AiResponseDto> aiResults) {
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new IllegalArgumentException("콜백: 해당 강의가 없습니다."));

        // 5. AI 응답 결과를 DB에 저장
        List<GeneratedContent> contentsToSave = aiResults.stream()
                .map(dto -> GeneratedContent.builder()
                        .lecture(lecture)
                        .contentType(ContentType.valueOf(dto.getContentType()))
                        .contentData(dto.getContentData())
                        .materialReferences(dto.getMaterialReferences())
                        .build())
                .collect(Collectors.toList());

        // 6. 강의 상태를 'COMPLETED'로 변경
        if (contentsToSave != null && !contentsToSave.isEmpty()) {
            generatedContentRepository.saveAll(contentsToSave);
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.COMPLETED);
        } else {
            lecture.updateAiGeneratedStatus(AiGeneratedStatus.FAILED);
        }
    }

}