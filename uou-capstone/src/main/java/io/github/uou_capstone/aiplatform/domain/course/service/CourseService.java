package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
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
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final CurrentUserResolver currentUserResolver;
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
        // 1. 현재 로그인한 사용자 정보 가져오기 + 선생님 권한 확인
        Teacher currentTeacher = currentUserResolver.getTeacher();

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

    private static final Set<String> COURSE_SORT_WHITELIST = Set.of("createdAt", "updatedAt", "title");
    private static final Sort COURSE_DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "updatedAt");

    @Transactional(readOnly = true) // 조회 기능이므로 readOnly = true 설정
    public PageResponse<CourseResponseDto> getAllCourses(Pageable rawPageable) {
        Pageable pageable = PageableSupport.validate(rawPageable, COURSE_SORT_WHITELIST, COURSE_DEFAULT_SORT);

        User currentUser = currentUserResolver.getUser();
        List<Course> courses;

        if (currentUser.getRole() == Role.TEACHER) {
            Teacher currentTeacher = currentUserResolver.getTeacher();
            courses = courseRepository.findByTeacher(currentTeacher);
        } else if (currentUser.getRole() == Role.STUDENT) {
            Student student = currentUserResolver.getStudent();
            courses = enrollmentRepository.findByStudent(student).stream()
                    .map(Enrollment::getCourse)
                    .distinct()
                    .collect(Collectors.toList());
        } else {
            courses = courseRepository.findAll();
        }

        Comparator<Course> comparator = buildCourseComparator(pageable.getSort());
        List<CourseResponseDto> sorted = courses.stream()
                .sorted(comparator)
                .map(CourseResponseDto::new)
                .collect(Collectors.toList());

        return PageResponse.ofSlice(sorted, pageable);
    }

    private Comparator<Course> buildCourseComparator(Sort sort) {
        Comparator<Course> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<Course> next = switch (order.getProperty()) {
                case "createdAt" -> Comparator.comparing(Course::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "updatedAt" -> Comparator.comparing(Course::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                case "title" -> Comparator.comparing(Course::getTitle,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                default -> throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "허용되지 않는 정렬 필드입니다: " + order.getProperty());
            };
            if (order.isDescending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator != null ? comparator : Comparator.comparing(Course::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
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
        Course course = courseRepository.findByIdWithLectures(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        User currentUser = currentUserResolver.getUser();

        if (currentUser.getRole() == Role.TEACHER) {
            Teacher currentTeacher = currentUserResolver.getTeacher();
            if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
        } else if (currentUser.getRole() == Role.STUDENT) {
            Student student = currentUserResolver.getStudent();
            if (!enrollmentRepository.existsByStudentAndCourse(student, course)) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
        } else {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        List<Lecture> orderedLectures = course.getLectures().stream()
                .sorted(Comparator.comparingInt(Lecture::getWeekNumber))
                .collect(Collectors.toList());

        List<Long> lectureIds = orderedLectures.stream()
                .map(Lecture::getId)
                .collect(Collectors.toList());

        Map<Long, List<Material>> materialsByLectureId = lectureIds.isEmpty()
                ? Map.of()
                : materialRepository.findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc(lectureIds).stream()
                        .collect(Collectors.groupingBy(m -> m.getLecture().getId()));

        Map<Long, List<ExamSession>> examSessionsByLectureId = lectureIds.isEmpty()
                ? Map.of()
                : examSessionRepository.findByLecture_IdIn(lectureIds).stream()
                        .collect(Collectors.groupingBy(e -> e.getLecture().getId()));

        List<LectureContentsDto> lectureContents = orderedLectures.stream()
                .map(lecture -> {
                    List<MaterialSummaryDto> materials = materialsByLectureId
                            .getOrDefault(lecture.getId(), List.of()).stream()
                            .map(MaterialSummaryDto::new)
                            .collect(Collectors.toList());
                    List<ExamSessionSummaryDto> examSessions = examSessionsByLectureId
                            .getOrDefault(lecture.getId(), List.of()).stream()
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

        Teacher currentTeacher = currentUserResolver.getTeacher();

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
        Teacher currentTeacher = currentUserResolver.getTeacher();

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
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
        Teacher currentTeacher = currentUserResolver.getTeacher();

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
