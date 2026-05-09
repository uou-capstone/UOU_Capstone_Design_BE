package io.github.uou_capstone.aiplatform.domain.course.discussion.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionListItemResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.dto.DiscussionUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.discussion.service.DiscussionService;
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

@Tag(name = "토론게시판 API", description = "강의실 토론·자유게시판 CRUD (학생도 작성 가능)")
@RestController
@RequestMapping("/api/courses/{courseId}/discussions")
@RequiredArgsConstructor
public class DiscussionController {

    private final DiscussionService discussionService;

    @Operation(summary = "토론 게시글 목록", description = "강의실 참가자(교사 또는 ACTIVE 수강생)가 페이지로 조회. 정렬: pinned DESC, createdAt DESC.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<PageResponse<DiscussionListItemResponseDto>> list(
            @PathVariable Long courseId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(discussionService.listDiscussions(courseId, pageable));
    }

    @Operation(summary = "토론 게시글 상세 (viewCount +1)")
    @GetMapping("/{discussionId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<DiscussionResponseDto> get(
            @PathVariable Long courseId,
            @PathVariable Long discussionId) {
        return ResponseEntity.ok(discussionService.getDiscussion(courseId, discussionId));
    }

    @Operation(summary = "토론 게시글 작성", description = "강의실 참가자(교사 또는 ACTIVE 수강생)가 새 게시글을 작성합니다.")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<DiscussionResponseDto> create(
            @PathVariable Long courseId,
            @Valid @RequestBody DiscussionCreateRequestDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(discussionService.createDiscussion(courseId, dto));
    }

    @Operation(summary = "토론 게시글 수정 (작성자만)")
    @PatchMapping("/{discussionId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<DiscussionResponseDto> update(
            @PathVariable Long courseId,
            @PathVariable Long discussionId,
            @Valid @RequestBody DiscussionUpdateRequestDto dto) {
        return ResponseEntity.ok(discussionService.updateDiscussion(courseId, discussionId, dto));
    }

    @Operation(summary = "토론 게시글 삭제 (작성자 또는 강의실 교사)")
    @DeleteMapping("/{discussionId}")
    @PreAuthorize("hasAnyAuthority('TEACHER', 'STUDENT')")
    public ResponseEntity<Void> delete(
            @PathVariable Long courseId,
            @PathVariable Long discussionId) {
        discussionService.deleteDiscussion(courseId, discussionId);
        return ResponseEntity.noContent().build();
    }
}
