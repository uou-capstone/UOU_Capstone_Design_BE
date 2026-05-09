package io.github.uou_capstone.aiplatform.domain.course.discussion.entity;

/**
 * 토론 게시글 카테고리.
 * - QUESTION: 질문
 * - FREE: 자유 게시
 * - RESOURCE: 자료 공유
 *
 * <p>레퍼런스에 있던 NOTICE 카테고리는 우리 별도 Notice 도메인이 담당하므로 제외.
 */
public enum DiscussionCategory {
    QUESTION,
    FREE,
    RESOURCE
}
