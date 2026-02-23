package io.github.uou_capstone.aiplatform.domain.material.generation;

/**
 * 강의 자료 생성 파이프라인의 단계
 */
public enum GenerationPhase {
    PHASE1,      // Analysis & Scope (기획)
    PHASE2,      // Interactive Briefing (기획 검토 및 확정)
    PHASE3,      // Content Generation (심층 조사 및 집필)
    PHASE4,      // Review & Verification (검증 및 수정)
    PHASE5,      // Final Assembly (최종 조립)
    COMPLETED,   // 완료
    FAILED       // 실패
}
