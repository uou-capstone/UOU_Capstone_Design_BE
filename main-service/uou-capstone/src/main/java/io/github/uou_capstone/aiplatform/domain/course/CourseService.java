package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.user.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.User;
import io.github.uou_capstone.aiplatform.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;

    @Transactional
    public Course createCourse(CourseCreateRequestDto requestDto) {
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
}
