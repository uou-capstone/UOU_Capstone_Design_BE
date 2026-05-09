package io.github.uou_capstone.aiplatform.domain.course.discussion.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCommentUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.service.DiscussionCommentService;
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

@Tag(name = "토론 댓글 API", description = "토론 게시판 댓글 CRUD (1단계 답글 허용)")
@RestController
@RequestMapping("/api/courses/{courseId}/discussions/{discussionId}/comments")
@RequiredArgsConstructor
public class DiscussionCommentController {

    private final DiscussionCommentService commentService;

    @Operation(summary = "토론 댓글 목록")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<PageResponse<DiscussionCommentResponseDto>> list(
            @PathVariable Long courseId,
            @PathVariable Long discussionId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(commentService.listComments(courseId, discussionId, pageable));
    }

    @Operation(summary = "토론 댓글 작성", description = "allowComments=true 인 게시글에만 작성 가능. parentCommentId 가 있으면 답글.")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<DiscussionCommentResponseDto> create(
            @PathVariable Long courseId,
            @PathVariable Long discussionId,
            @Valid @RequestBody DiscussionCommentCreateRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.createComment(courseId, discussionId, dto));
    }

    @Operation(summary = "토론 댓글 수정 (작성자만)")
    @PatchMapping("/{commentId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<DiscussionCommentResponseDto> update(
            @PathVariable Long courseId,
            @PathVariable Long discussionId,
            @PathVariable Long commentId,
            @Valid @RequestBody DiscussionCommentUpdateRequestDto dto) {
        return ResponseEntity.ok(commentService.updateComment(courseId, discussionId, commentId, dto));
    }

    @Operation(summary = "토론 댓글 삭제 (작성자 또는 강의실 교사)")
    @DeleteMapping("/{commentId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Void> delete(
            @PathVariable Long courseId,
            @PathVariable Long discussionId,
            @PathVariable Long commentId) {
        commentService.deleteComment(courseId, discussionId, commentId);
        return ResponseEntity.noContent().build();
    }
}
