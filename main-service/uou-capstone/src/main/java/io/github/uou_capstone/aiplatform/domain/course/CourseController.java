package io.github.uou_capstone.aiplatform.domain.course;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')") // 이 API는 'TEACHER' 역할을 가진 사용자만 호출 가능
    public ResponseEntity<CourseResponseDto> createCourse(@RequestBody CourseCreateRequestDto requestDto) {
        // 1. Service를 호출하여 과목 생성 로직 수행
        Course newCourse = courseService.createCourse(requestDto);

        // 2. 생성된 Course Entity를 CourseResponseDto로 변환
        CourseResponseDto responseDto = new CourseResponseDto(newCourse);

        // 3. HTTP 상태 코드 201(Created)와 함께 응답 본문에 DTO를 담아 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }
}