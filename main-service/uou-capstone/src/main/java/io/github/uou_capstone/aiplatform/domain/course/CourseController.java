package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final EnrollmentService enrollmentService;

    @Operation(summary = "과목생성", description = "선생님이 과목을 생성합니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')") // 이 API는 'TEACHER' 역할을 가진 사용자만 호출 가능
    public ResponseEntity<CourseResponseDto> createCourse(@RequestBody CourseCreateRequestDto requestDto) {
        // 1. Service를 호출하여 과목 생성 로직 수행
        Course newCourse = courseService.createCourse(requestDto);

        // 2. 생성된 Course Entity를 CourseResponseDto로 변환
        CourseResponseDto responseDto = new CourseResponseDto(newCourse);

        // 3. HTTP 상태 코드 201(Created)와 함께 응답 본문에 DTO를 담아 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    @Operation(summary = "전체 과목 목록 조회", description = "생성된 모든 과목의 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<List<CourseResponseDto>> getAllCourses() {
        List<CourseResponseDto> courses = courseService.getAllCourses();
        return ResponseEntity.ok(courses);
    }

    @Operation(summary = "과목 상세 조회", description = "특정 과목의 상세 정보와 강의 목록을 조회합니다.")
    @GetMapping("/{courseId}")
    public ResponseEntity<CourseResponseDto> getCourseById(@PathVariable Long courseId) {
        Course course = courseService.getCourseById(courseId);
        return ResponseEntity.ok(new CourseResponseDto(course));
    }


    @Operation(summary = "수강 신청", description = "학생이 특정 과목에 수강 신청을 합니다.")
    @PostMapping("/{courseId}/enroll")
    @PreAuthorize("hasAuthority('STUDENT')") // 학생만 호출 가능
    public ResponseEntity<String> enrollCourse(@PathVariable Long courseId) {
        enrollmentService.enrollCourse(courseId);
        return ResponseEntity.status(HttpStatus.CREATED).body("수강 신청이 완료되었습니다.");
    }
}