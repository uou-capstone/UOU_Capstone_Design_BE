package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
    import io.github.uou_capstone.aiplatform.domain.course.dto.CourseUpdateRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "과목 API", description = "인증된 사용자(특히 선생님과 학생)가 과목을 생성, 조회, 수정, 삭제하고 수강 신청하는 흐름을 다룸")
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

    @Operation(summary = "과목 정보 수정", description = "선생님이 자신이 개설한 과목의 제목 또는 설명을 수정합니다.")
    @PutMapping("/{courseId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CourseResponseDto> updateCourse(
            @PathVariable Long courseId,
            @RequestBody CourseUpdateRequestDto requestDto) {

        Course updatedCourse = courseService.updateCourse(courseId, requestDto);
        return ResponseEntity.ok(new CourseResponseDto(updatedCourse));
    }

    @Operation(summary = "과목 삭제", description = "선생님이 자신이 개설한 과목을 삭제합니다.")
    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteCourse(@PathVariable Long courseId) {
        courseService.deleteCourse(courseId);

        // 삭제 성공 시 204 No Content 응답 반환
        return ResponseEntity.noContent().build();
    }
}