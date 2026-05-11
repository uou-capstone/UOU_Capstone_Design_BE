package io.github.uou_capstone.aiplatform.domain.course.notice.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCommentUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.service.NoticeCommentService;
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

@Tag(name = "공지 댓글 API", description = "강의실 공지사항 댓글 CRUD (1단계 답글 허용)")
@RestController
@RequestMapping("/api/courses/{courseId}/notices/{noticeId}/comments")
@RequiredArgsConstructor
public class NoticeCommentController {

    private final NoticeCommentService commentService;

    @Operation(summary = "공지 댓글 목록 조회",
            description = "강의실 참가자가 댓글을 페이지로 조회합니다. 기본 정렬: createdAt ASC.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<PageResponse<NoticeCommentResponseDto>> listComments(
            @PathVariable Long courseId,
            @PathVariable Long noticeId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(commentService.listComments(courseId, noticeId, pageable));
    }

    @Operation(summary = "공지 댓글 작성",
            description = "강의실 참가자(교사 또는 ACTIVE 수강생)가 댓글을 작성합니다. "
                    + "parentCommentId 가 있으면 답글 (1단계 까지만).")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<NoticeCommentResponseDto> createComment(
            @PathVariable Long courseId,
            @PathVariable Long noticeId,
            @Valid @RequestBody NoticeCommentCreateRequestDto dto) {
        NoticeCommentResponseDto created = commentService.createComment(courseId, noticeId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "공지 댓글 수정 (작성자)")
    @PatchMapping("/{commentId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<NoticeCommentResponseDto> updateComment(
            @PathVariable Long courseId,
            @PathVariable Long noticeId,
            @PathVariable Long commentId,
            @Valid @RequestBody NoticeCommentUpdateRequestDto dto) {
        return ResponseEntity.ok(commentService.updateComment(courseId, noticeId, commentId, dto));
    }

    @Operation(summary = "공지 댓글 삭제 (작성자 또는 강의실 교사)")
    @DeleteMapping("/{commentId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long courseId,
            @PathVariable Long noticeId,
            @PathVariable Long commentId) {
        commentService.deleteComment(courseId, noticeId, commentId);
        return ResponseEntity.noContent().build();
    }
}
