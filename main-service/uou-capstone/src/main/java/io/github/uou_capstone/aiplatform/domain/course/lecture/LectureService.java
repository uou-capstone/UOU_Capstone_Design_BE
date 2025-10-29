package io.github.uou_capstone.aiplatform.domain.course.lecture;

import io.github.uou_capstone.aiplatform.domain.course.Course;
import io.github.uou_capstone.aiplatform.domain.course.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.LectureDetailResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LectureService {

    private final CourseRepository courseRepository;
    private final LectureRepository lectureRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;

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
}