package io.github.uou_capstone.aiplatform.domain.material.generation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * FastAPI 강의 자료 생성 요청 DTO
 * AI 서비스(FastAPI)로 전송하는 요청 형식
 */
@Getter
@Setter
public class FastApiMaterialRequestDto {
    private Long sessionId;
    private String phase; // "phase1", "phase2", "phase3", "phase4", "phase5"
    private String keyword; // Phase 1용
    private DraftPlanDto draftPlan; // Phase 2용
    private String feedback; // Phase 2용
    private FinalizedBriefDto finalizedBrief; // Phase 3용
    private ChapterContentListDto chapterContentList; // Phase 4용
    private VerifiedContentDto verifiedContent; // Phase 5용
}
