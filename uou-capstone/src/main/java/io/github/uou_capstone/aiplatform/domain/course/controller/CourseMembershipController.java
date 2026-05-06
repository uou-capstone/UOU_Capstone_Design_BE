package io.github.uou_capstone.aiplatform.domain.course.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseStudentItemDto;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseMembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "강의실 수강생 관리 API",
     description = "교사가 본인 강의실의 수강생 목록을 조회하고 제거/차단하는 흐름")
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseMembershipController {

    private final CourseMembershipService membershipService;

    @Operation(summary = "강의실 수강생 목록 조회 (교사)",
               description = "본인 강의실의 활성 수강생 목록을 페이지네이션으로 반환합니다. "
                       + "정렬 허용 필드: createdAt (=enrolledAt). 기본 createdAt,desc.")
    @GetMapping("/{courseId}/students")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<PageResponse<CourseStudentItemDto>> listStudents(
            @PathVariable Long courseId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(membershipService.listStudents(courseId, pageable));
    }

    @Operation(summary = "강의실 수강생 제거 (교사)",
               description = "본인 강의실에서 학생의 수강 관계(Enrollment)를 제거합니다. "
                       + "재가입 요청은 가능하며, 차단이 필요하면 별도 block 엔드포인트를 사용하세요.")
    @DeleteMapping("/{courseId}/students/{studentId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> removeStudent(@PathVariable Long courseId,
                                              @PathVariable Long studentId) {
        membershipService.removeStudent(courseId, studentId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "강의실 수강생 차단 (교사)",
               description = "본인 강의실에서 학생을 제거하고 동일 강의실로의 재가입을 차단합니다. "
                       + "활성 수강 관계가 있으면 함께 끊기며, BLOCKED 가입 요청 마커가 등록됩니다.")
    @PostMapping("/{courseId}/students/{studentId}/block")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> blockStudent(@PathVariable Long courseId,
                                             @PathVariable Long studentId) {
        membershipService.blockStudent(courseId, studentId);
        return ResponseEntity.noContent().build();
    }
}
