package io.github.uou_capstone.aiplatform.domain.exam.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 프로필 대화 1턴 요청 DTO
 * 문서: ai-service-endpoint-request.md §2.3
 * - lecture_content 필수, existing_profile / user_message / exam_type 선택
 */
@Getter
@Setter
public class ProfileConversationRequestDto {

    @NotBlank(message = "강의 내용(lecture_content)은 필수입니다.")
    private String lectureContent;

    /** 시험 유형 (예: Flash_Card, OX_Problem). 선택 */
    private String examType;

    /** 이전 턴에서 받은 updated_profile. 첫 턴이면 null */
    private TestProfileDto existingProfile;

    /** 사용자 메시지. 첫 턴(인사)이면 빈 문자열 또는 null */
    private String userMessage;
}
