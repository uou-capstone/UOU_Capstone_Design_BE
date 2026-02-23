package io.github.uou_capstone.aiplatform.domain.exam.entity;

/**
 * 토론형 시험 Phase
 */
public enum DebatePhase {
    PHASE1,  // 모드 설정 및 주제 선정
    PHASE2,  // 경쟁 루프 (사용자 입력 → AI 반박)
    PHASE3   // 로그 데이터 생성
}
