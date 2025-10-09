package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.github.uou_capstone.aiplatform.domain.user.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;

    @Transactional
    public Course createCourse(CourseCreateRequestDto requestDto) { //과목 생성
        // 1. 현재 로그인한 사용자 정보 가져오기
        String userEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        // 2. 현재 사용자가 선생님(Teacher)인지 확인하기
        Teacher currentTeacher = teacherRepository.findByUser_Id(currentUser.getId())
                .orElseThrow(() -> new IllegalArgumentException("과목을 생성할 권한이 없습니다. (선생님 계정이 아님)"));

        // 3. Course Entity 생성
        Course newCourse = Course.builder()
                .teacher(currentTeacher)
                .title(requestDto.getTitle())
                .description(requestDto.getDescription())
                .build();

        // 4. 생성된 Course를 DB에 저장하고 반환
        return courseRepository.save(newCourse);
    }

    @Transactional(readOnly = true) // 조회 기능이므로 readOnly = true 설정
    public List<CourseResponseDto> getAllCourses() { //과목 전체 조회
        // 1. DB에서 모든 Course를 찾아 List<Course> 형태로 가져옴
        List<Course> courses = courseRepository.findAll();

        // 2. List<Course>를 List<CourseResponseDto>로 변환하여 반환
        return courses.stream()
                .map(CourseResponseDto::new) // 각 Course 객체를 CourseResponseDto로 변환
                .collect(Collectors.toList());
    }


    @Transactional(readOnly = true)
    public Course getCourseById(Long courseId) { //과목 id 상세 조회
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new IllegalArgumentException("해당 ID의 과목이 없습니다."));
    }
}
