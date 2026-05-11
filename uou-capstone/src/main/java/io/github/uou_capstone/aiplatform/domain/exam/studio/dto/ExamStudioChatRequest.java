package io.github.uou_capstone.aiplatform.domain.exam.studio.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Exam Studio Chat 스트림 요청.
 *
 * <p>FastAPI 측으로 그대로 forward — operations[] (patchExamSettings / appendQuestions / replaceQuestion)
 * 은 응답 {@code done.data.operations[]} 로 받는다.
 */
@Getter
@Setter
@NoArgsConstructor
public class ExamStudioChatRequest {

    @NotBlank
    private String contextId;

    /** {role, content} 형태 메시지 배열. {@code message} 단일 사용 시 비워둘 수 있음. */
    private List<Map<String, Object>> messages;

    /** 단발 사용자 메시지. {@code messages}와 동시에 사용 시 FastAPI 측이 messages 우선. */
    private String message;

    /** FE가 현재 편집 중인 시험 초안 (free schema — operations 가 patch 대상). */
    private Map<String, Object> currentDraft;

    /** 현재 KST ISO 시각 (선택). */
    private String currentKstIso;

    /** IANA timezone (선택). */
    private String timeZone;

    /** PDF 외 추가 자료 텍스트 (선택). */
    private String sourceText;

    /** 모델 명시 (선택 — null이면 FastAPI 기본 모델). */
    private String model;
}
