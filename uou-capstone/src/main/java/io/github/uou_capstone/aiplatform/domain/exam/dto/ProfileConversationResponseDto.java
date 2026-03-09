package io.github.uou_capstone.aiplatform.domain.exam.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 프로필 대화 1턴 응답 DTO
 * 문서: ai-service-endpoint-request.md §2.4
 * - status: INCOMPLETE(다음 질문) | COMPLETE(프로필 확정)
 * - agent_message: 사용자에게 보여줄 문장
 * - updated_profile: 현재(또는 최종) 프로필
 */
@Getter
@Setter
@Builder
public class ProfileConversationResponseDto {

    /** "INCOMPLETE" | "COMPLETE" */
    private String status;

    /** 에이전트가 사용자에게 보여줄 문장 (질문 또는 완료 멘트) */
    private String agentMessage;

    /** 아직 비어 있다고 판단한 필드 이름 (예: learning_goal, user_status) */
    private List<String> missingInfo;

    /** 현재(또는 최종) 프로필. COMPLETE 시 이 값을 시험 생성 요청의 userProfile로 전달 */
    private TestProfileDto updatedProfile;
}
