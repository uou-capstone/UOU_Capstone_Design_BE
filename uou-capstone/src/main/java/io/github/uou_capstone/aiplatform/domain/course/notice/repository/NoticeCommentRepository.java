package io.github.uou_capstone.aiplatform.domain.course.notice.repository;

import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoticeCommentRepository extends JpaRepository<NoticeComment, Long> {

    Page<NoticeComment> findByNotice(Notice notice, Pageable pageable);

    /**
     * commentId 가 해당 notice 소속인지까지 검증. 다른 게시글 댓글 ID 를 잘못 넘기는 케이스 차단.
     * 1단계 답글 검증 시에도 parentCommentId 를 이 메서드로 조회.
     */
    Optional<NoticeComment> findByIdAndNotice(Long id, Notice notice);
}
