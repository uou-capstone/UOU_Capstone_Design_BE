package io.github.uou_capstone.aiplatform.domain.course.report.classroom.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.entity.ClassroomReport;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class ClassroomReportResponse {

    private final Long courseId;
    private final String summaryMarkdown;
    private final List<Object> highlights;
    private final List<Object> risks;
    private final List<Object> coachingPriorities;
    private final String source;
    private final boolean fallbackUsed;
    private final String reason;
    private final String confidence;
    private final LocalDateTime generatedAt;

    public ClassroomReportResponse(ClassroomReport entity, ObjectMapper objectMapper) {
        this.courseId = entity.getCourse() == null ? null : entity.getCourse().getId();
        this.summaryMarkdown = entity.getSummaryMarkdown();
        this.highlights = parseJsonArray(objectMapper, entity.getHighlightsJson());
        this.risks = parseJsonArray(objectMapper, entity.getRisksJson());
        this.coachingPriorities = parseJsonArray(objectMapper, entity.getCoachingPrioritiesJson());
        this.source = entity.getSource();
        this.fallbackUsed = entity.isFallbackUsed();
        this.reason = entity.getFallbackReason();
        this.confidence = entity.getConfidence();
        this.generatedAt = entity.getGeneratedAt();
    }

    private static List<Object> parseJsonArray(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return om.readValue(json, new TypeReference<List<Object>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
