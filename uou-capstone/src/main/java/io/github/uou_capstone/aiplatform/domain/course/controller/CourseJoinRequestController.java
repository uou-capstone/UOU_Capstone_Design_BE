package io.github.uou_capstone.aiplatform.domain.course.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestListItemDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.JoinRequestBulkRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.JoinRequestBulkResultDto;
import io.github.uou_capstone.aiplatform.domain.course.dto.MyJoinRequestItemDto;
import io.github.uou_capstone.aiplatform.domain.course.entity.CourseJoinRequestStatus;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseJoinRequestService;
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

@Tag(name = "강의실 가입 요청 API",
     description = "학생의 강의실 가입 요청 생성 및 교사의 승인/거절/차단 흐름")
@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
public class CourseJoinRequestController {

    private final CourseJoinRequestService joinRequestService;

    @Operation(summary = "강의실 가입 요청 (학생)",
               description = "학생이 초대 코드로 강의실 가입을 요청합니다. 교사 승인 후 수강 등록(Enrollment)이 생성됩니다.")
    @PostMapping("/join-requests")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<CourseJoinRequestResponseDto> createJoinRequest(
            @Valid @RequestBody CourseJoinRequestCreateDto dto) {
        CourseJoinRequestResponseDto response = joinRequestService.createJoinRequest(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "내 강의실 가입 요청 목록 조회 (학생)",
               description = "현재 로그인 학생의 가입 요청을 상태 무관하게 최신순으로 반환합니다. 정렬 허용 필드: createdAt / updatedAt.")
    @GetMapping("/join-requests/me")
    @PreAuthorize("hasAuthority('STUDENT')")
    public ResponseEntity<PageResponse<MyJoinRequestItemDto>> getMyJoinRequests(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(joinRequestService.getMyJoinRequests(pageable));
    }

    @Operation(summary = "강의실 가입 요청 목록 조회 (교사)",
               description = "본인 강의실의 가입 요청을 상태별로 조회합니다. 기본 status=PENDING, 정렬 createdAt,desc. 정렬 허용 필드: createdAt / updatedAt.")
    @GetMapping("/{courseId}/join-requests")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<PageResponse<CourseJoinRequestListItemDto>> getJoinRequests(
            @PathVariable Long courseId,
            @RequestParam(name = "status", required = false, defaultValue = "PENDING") CourseJoinRequestStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(joinRequestService.getJoinRequests(courseId, status, pageable));
    }

    @Operation(summary = "강의실 가입 요청 승인 (교사)",
               description = "PENDING 상태의 가입 요청을 승인합니다. Enrollment가 새로 생성되고 요청은 APPROVED로 전이됩니다.")
    @PostMapping("/{courseId}/join-requests/{requestId}/approve")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> approveJoinRequest(@PathVariable Long courseId,
                                                   @PathVariable Long requestId) {
        joinRequestService.approveJoinRequest(courseId, requestId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "강의실 가입 요청 거절 (교사)",
               description = "PENDING 상태의 가입 요청을 거절합니다. 학생은 동일 강의실에 다시 요청할 수 있습니다.")
    @PostMapping("/{courseId}/join-requests/{requestId}/reject")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> rejectJoinRequest(@PathVariable Long courseId,
                                                  @PathVariable Long requestId) {
        joinRequestService.rejectJoinRequest(courseId, requestId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "강의실 가입 요청 차단 (교사)",
               description = "PENDING 상태의 가입 요청을 차단합니다. 해당 학생은 이후 같은 강의실에 다시 요청할 수 없습니다.")
    @PostMapping("/{courseId}/join-requests/{requestId}/block")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> blockJoinRequest(@PathVariable Long courseId,
                                                 @PathVariable Long requestId) {
        joinRequestService.blockJoinRequest(courseId, requestId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "강의실 가입 요청 일괄 승인 (교사)",
               description = "PENDING 상태의 가입 요청 다수를 일괄 승인합니다. 한 건 실패가 다른 건의 처리를 막지 않으며, "
                       + "결과는 요청 ID 별 success/errorCode 목록으로 반환합니다. 한 번에 최대 100건.")
    @PostMapping("/{courseId}/join-requests/bulk/approve")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<JoinRequestBulkResultDto> approveJoinRequestsBulk(
            @PathVariable Long courseId,
            @Valid @RequestBody JoinRequestBulkRequestDto dto) {
        return ResponseEntity.ok(joinRequestService.approveJoinRequestsBulk(courseId, dto));
    }

    @Operation(summary = "강의실 가입 요청 일괄 거절 (교사)",
               description = "PENDING 상태의 가입 요청 다수를 일괄 거절합니다. 결과 형식은 일괄 승인과 동일하며, "
                       + "거절된 학생은 동일 강의실에 다시 요청을 보낼 수 있습니다.")
    @PostMapping("/{courseId}/join-requests/bulk/reject")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<JoinRequestBulkResultDto> rejectJoinRequestsBulk(
            @PathVariable Long courseId,
            @Valid @RequestBody JoinRequestBulkRequestDto dto) {
        return ResponseEntity.ok(joinRequestService.rejectJoinRequestsBulk(courseId, dto));
    }
}
