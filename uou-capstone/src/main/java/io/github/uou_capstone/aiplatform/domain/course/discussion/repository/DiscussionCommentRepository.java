package io.github.uou_capstone.aiplatform.domain.course.discussion.repository;

import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.Discussion;
import io.github.uou_capstone.aiplatform.domain.course.discussion.entity.DiscussionComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DiscussionCommentRepository extends JpaRepository<DiscussionComment, Long> {

    Page<DiscussionComment> findByDiscussion(Discussion discussion, Pageable pageable);

    /** 같은 게시글 소속인지까지 검증 — 다른 게시글 댓글 ID 차단 / 1단계 답글 검증용. */
    Optional<DiscussionComment> findByIdAndDiscussion(Long id, Discussion discussion);
}
