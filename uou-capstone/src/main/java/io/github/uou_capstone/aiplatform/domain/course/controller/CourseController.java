package io.github.uou_capstone.aiplatform.domain.course.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseContentsDeleteRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseContentsResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseService;
import io.github.uou_capstone.aiplatform.domain.course.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "강의실(Course) API", description = "인증된 사용자(특히 선생님과 학생)가 강의실을 생성, 조회, 수정, 삭제하고 수강 신청하는 흐름을 다룸")
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;
    private final EnrollmentService enrollmentService;

    @Operation(summary = "강의실 생성", description = "선생님이 강의실(Course)을 생성합니다. 인증코드가 자동으로 생성됩니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')") // 이 API는 'TEACHER' 역할을 가진 사용자만 호출 가능
    public ResponseEntity<CourseResponseDto> createCourse(@Valid @RequestBody CourseCreateRequestDto requestDto) {
        // 1. Service를 호출하여 강의실 생성 로직 수행
        Course newCourse = courseService.createCourse(requestDto);

        // 2. 생성된 Course Entity를 CourseResponseDto로 변환
        CourseResponseDto responseDto = new CourseResponseDto(newCourse);

        // 3. HTTP 상태 코드 201(Created)와 함께 응답 본문에 DTO를 담아 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    @Operation(summary = "전체 강의실 목록 조회",
            description = "강의실 목록을 페이지 단위로 조회합니다. 정렬 허용 필드: createdAt / updatedAt / title. 기본 정렬: updatedAt,desc. size 최대 100.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<PageResponse<CourseResponseDto>> getAllCourses(
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(courseService.getAllCourses(pageable));
    }

    @Operation(summary = "강의실 상세 조회", description = "특정 강의실의 상세 정보와 강의(Lecture) 목록을 조회합니다.")
    @GetMapping("/{courseId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<CourseResponseDto> getCourseById(@PathVariable Long courseId) {
        Course course = courseService.getCourseById(courseId);
        return ResponseEntity.ok(new CourseResponseDto(course));
    }

    @Operation(summary = "강의실 n주차 자료 조회", description = "강의실 내 주차별로 생성해둔 강의자료·시험 목록을 조회합니다. (강의실/강의 조회와 동일 권한)")
    @GetMapping("/{courseId}/contents")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<CourseContentsResponseDto> getCourseContents(@PathVariable Long courseId) {
        CourseContentsResponseDto contents = courseService.getCourseContents(courseId);
        return ResponseEntity.ok(contents);
    }

    @Operation(summary = "강의실 n주차 자료 일괄 삭제", description = "강의실 내 주차별로 생성해둔 강의자료·시험·생성세션을 선택하여 일괄 삭제합니다. (선생님만 호출 가능, contents 조회와 동일 스코프)")
    @PostMapping("/{courseId}/contents/delete")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteCourseContents(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseContentsDeleteRequestDto requestDto) {
        courseService.deleteCourseContents(courseId, requestDto);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "[Deprecated] 초대 코드 즉시 입장",
            description = "초대 코드로 즉시 수강 등록(Enrollment)되는 호환용 경로입니다. " +
                    "신규 승인형 흐름은 POST /api/courses/join-requests 를 사용하세요. " +
                    "본 경로는 호환성 유지를 위해 남겨졌으며 추후 라운드에서 제거될 수 있습니다.",
            deprecated = true
    )
    @PostMapping("/join")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<String> joinCourse(@RequestParam("code") String invitationCode) {
        enrollmentService.enrollCourseByCode(invitationCode);
        return ResponseEntity.status(HttpStatus.CREATED).body("강의실 입장이 완료되었습니다.");
    }

    @Operation(summary = "강의실 정보 수정", description = "선생님이 자신이 개설한 강의실의 제목 또는 설명을 수정합니다.")
    @PutMapping("/{courseId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<CourseResponseDto> updateCourse(
            @PathVariable Long courseId,
            @Valid @RequestBody CourseUpdateRequestDto requestDto) {

        Course updatedCourse = courseService.updateCourse(courseId, requestDto);
        return ResponseEntity.ok(new CourseResponseDto(updatedCourse));
    }

    @Operation(summary = "강의실 삭제", description = "선생님이 자신이 개설한 강의실을 삭제합니다.")
    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteCourse(@PathVariable Long courseId) {
        courseService.deleteCourse(courseId);

        // 삭제 성공 시 204 No Content 응답 반환
        return ResponseEntity.noContent().build();
    }
}
