package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseContentsDeleteRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseContentsResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.ExamSessionSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.LectureContentsDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.MaterialSummaryDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamProfileRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.exam.service.ExamGenerationService;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSession;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.generation.service.MaterialGenerationService;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.material.service.MaterialService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MaterialRepository materialRepository;
    private final ExamSessionRepository examSessionRepository;
    private final GenerationSessionRepository generationSessionRepository;
    private final ExamProfileRepository examProfileRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final MaterialService materialService;
    private final ExamGenerationService examGenerationService;
    private final MaterialGenerationService materialGenerationService;

    @Transactional
    public Course createCourse(CourseCreateRequestDto requestDto) { //강의실 생성
        // 1. 현재 로그인한 사용자 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        // 2. 현재 사용자가 선생님(Teacher)인지 확인하기
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN)); // 선생님 권한 없음

        // 3. 고유한 인증코드 생성 (UUID 사용)
        String invitationCode = UUID.randomUUID().toString();

        // 4. Course Entity 생성
        Course newCourse = Course.builder()
                .teacher(currentTeacher)
                .title(requestDto.getTitle())
                .description(requestDto.getDescription())
                .invitationCode(invitationCode)
                .build();

        // 5. 생성된 Course를 DB에 저장하고 반환
        return courseRepository.save(newCourse);
    }

    @Transactional(readOnly = true) // 조회 기능이므로 readOnly = true 설정
    public List<CourseResponseDto> getAllCourses() { //강의실 전체 조회
        
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        List<Course> courses;

        if (currentUser.getRole() == Role.TEACHER) {
            Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND)); // 선생님 정보 없음
            // 생성일 내림차순 정렬
            courses = courseRepository.findByTeacherOrderByCreatedAtDesc(currentTeacher);
        } else if (currentUser.getRole() == Role.STUDENT) {
            Student student = studentRepository.findById(currentUser.getId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND)); // 학생 정보 없음
            // 학생이 수강 중인 강의실 목록 (최신순 정렬)
            courses = enrollmentRepository.findByStudent(student).stream()
                    .map(Enrollment::getCourse)
                    .distinct()
                    .sorted(Comparator.comparing(Course::getCreatedAt).reversed()) // 메모리 내 정렬
                    .collect(Collectors.toList());
        } else {
            courses = courseRepository.findAll(); // 관리자용 (필요시 정렬 추가)
        }

        return courses.stream()
                .map(CourseResponseDto::new)
                .collect(Collectors.toList());
    }


    @Transactional(readOnly = true)
    public Course getCourseById(Long courseId) { //강의실 id 상세 조회
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
    }

    /**
     * 강의실 내 n주차별로 생성해둔 강의자료·시험 목록 조회 (강의실 조회 / 강의 조회 API와 동일한 권한)
     */
    @Transactional(readOnly = true)
    public CourseContentsResponseDto getCourseContents(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        if (currentUser.getRole() == Role.TEACHER) {
            Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
            if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
        } else if (currentUser.getRole() == Role.STUDENT) {
            Student student = studentRepository.findById(currentUser.getId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
            if (!enrollmentRepository.existsByStudentAndCourse(student, course)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
        } else {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        List<LectureContentsDto> lectureContents = course.getLectures().stream()
                .sorted(Comparator.comparingInt(Lecture::getWeekNumber))
                .map(lecture -> {
                    List<MaterialSummaryDto> materials = materialRepository
                            .findByLecture_IdOrderByCreatedAtDesc(lecture.getId()).stream()
                            .map(MaterialSummaryDto::new)
                            .collect(Collectors.toList());
                    List<ExamSessionSummaryDto> examSessions = examSessionRepository
                            .findByLecture(lecture).stream()
                            .map(ExamSessionSummaryDto::new)
                            .collect(Collectors.toList());
                    return new LectureContentsDto(
                            lecture.getId(),
                            lecture.getTitle(),
                            lecture.getWeekNumber(),
                            materials,
                            examSessions
                    );
                })
                .collect(Collectors.toList());

        return new CourseContentsResponseDto(course.getId(), course.getTitle(), lectureContents);
    }

    /**
     * 강의실 내 주차별로 생성해둔 강의자료·시험·생성세션을 일괄 삭제합니다.
     * (강의실 자료 조회 GET /api/courses/{courseId}/contents 와 동일한 스코프, 선생님만 호출 가능)
     */
    @Transactional
    public void deleteCourseContents(Long courseId, CourseContentsDeleteRequestDto requestDto) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        List<Long> materialIds = requestDto.getMaterialIds() != null ? requestDto.getMaterialIds() : List.of();
        List<Long> examSessionIds = requestDto.getExamSessionIds() != null ? requestDto.getExamSessionIds() : List.of();
        List<Long> generationSessionIds = requestDto.getGenerationSessionIds() != null ? requestDto.getGenerationSessionIds() : List.of();

        for (Long materialId : materialIds) {
            Material material = materialRepository.findById(materialId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.FILE_NOT_FOUND));
            if (!material.getLecture().getCourse().getId().equals(courseId)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            materialService.deleteMaterial(materialId);
        }

        for (Long examSessionId : examSessionIds) {
            ExamSession session = examSessionRepository.findById(examSessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
            if (!session.getLecture().getCourse().getId().equals(courseId)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            examGenerationService.deleteExamSession(examSessionId);
        }

        for (Long sessionId : generationSessionIds) {
            GenerationSession session = generationSessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.SESSION_NOT_FOUND));
            if (!session.getLecture().getCourse().getId().equals(courseId)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            materialGenerationService.deleteGenerationSession(sessionId);
        }
    }

    @Transactional
    public Course updateCourse(Long courseId, CourseUpdateRequestDto requestDto) {
        // 1. 수정할 강의실 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의실의 선생님인지 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN)); // 선생님 아님

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN); // 본인 강의실 아님
        }

        // 3. Entity 업데이트 DTO에 값이 있을 경우에만 수정
        course.update(requestDto.getTitle(), requestDto.getDescription());

        return course; // 업데이트된 Course 객체 반환
    }

    @Transactional
    public void deleteCourse(Long courseId) {
        // 1. 삭제할 강의실 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의실의 선생님인지 확인
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. 강의(lecture)를 참조하는 자식 테이블 먼저 삭제 (FK 제약으로 인한 삭제 실패 방지)
        generatedContentRepository.clearSessionByCourseId(courseId);  // generated_content.session_id 참조 해제
        generationSessionRepository.deleteByLectureCourseId(courseId);
        examSessionRepository.deleteByLectureCourseId(courseId);
        examProfileRepository.deleteByLectureCourseId(courseId);
        materialRepository.deleteByLectureCourseId(courseId);

        // 4. 강의실 삭제 (cascade: lectures -> materials, generated_contents, student_inquiries 등)
        courseRepository.delete(course);
    }
}
