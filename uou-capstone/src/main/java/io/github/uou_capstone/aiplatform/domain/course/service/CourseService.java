package io.github.uou_capstone.aiplatform.domain.course.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
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

        // 3. 강의실 삭제
        courseRepository.delete(course);
    }
}
