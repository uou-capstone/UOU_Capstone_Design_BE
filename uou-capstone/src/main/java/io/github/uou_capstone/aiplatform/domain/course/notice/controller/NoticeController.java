package io.github.uou_capstone.aiplatform.domain.course.notice.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeListItemResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "공지사항 API", description = "강의실 공지사항 CRUD")
@RestController
@RequestMapping("/api/courses/{courseId}/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "공지 목록 조회",
            description = "강의실 참가자(교사 또는 ACTIVE 수강생)가 공지를 페이지로 조회합니다. "
                    + "기본 정렬: pinned DESC, createdAt DESC. 정렬 허용 필드: createdAt / updatedAt / pinned.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<PageResponse<NoticeListItemResponseDto>> listNotices(
            @PathVariable Long courseId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(noticeService.listNotices(courseId, pageable));
    }

    @Operation(summary = "공지 상세 조회",
            description = "강의실 참가자가 공지 본문을 조회합니다.")
    @GetMapping("/{noticeId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<NoticeResponseDto> getNotice(
            @PathVariable Long courseId,
            @PathVariable Long noticeId) {
        return ResponseEntity.ok(noticeService.getNotice(courseId, noticeId));
    }

    @Operation(summary = "공지 작성 (교사)",
            description = "강의실 교사가 새 공지를 발행합니다. ACTIVE 수강생 전체에게 알림이 발송됩니다.")
    @PostMapping
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<NoticeResponseDto> createNotice(
            @PathVariable Long courseId,
            @Valid @RequestBody NoticeCreateRequestDto dto) {
        NoticeResponseDto created = noticeService.createNotice(courseId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "공지 수정 (작성자)",
            description = "공지 작성 교사 본인이 공지 내용을 수정합니다.")
    @PatchMapping("/{noticeId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<NoticeResponseDto> updateNotice(
            @PathVariable Long courseId,
            @PathVariable Long noticeId,
            @Valid @RequestBody NoticeUpdateRequestDto dto) {
        return ResponseEntity.ok(noticeService.updateNotice(courseId, noticeId, dto));
    }

    @Operation(summary = "공지 삭제 (작성자 또는 강의실 교사)",
            description = "공지 작성자 또는 강의실 교사가 공지를 삭제합니다.")
    @DeleteMapping("/{noticeId}")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<Void> deleteNotice(
            @PathVariable Long courseId,
            @PathVariable Long noticeId) {
        noticeService.deleteNotice(courseId, noticeId);
        return ResponseEntity.noContent().build();
    }
}
