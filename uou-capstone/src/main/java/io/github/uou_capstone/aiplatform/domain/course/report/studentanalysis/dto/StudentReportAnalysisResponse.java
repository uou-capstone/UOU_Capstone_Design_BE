package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
public class StudentReportAnalysisResponse {

    private final Long courseId;
    private final Long studentId;
    private final Map<String, Object> analysis;
    private final String summaryMarkdown;
    private final String source;
    private final boolean fallbackUsed;
    private final String reason;
    private final String confidence;
    private final LocalDateTime generatedAt;

    public StudentReportAnalysisResponse(StudentReportAnalysis entity, ObjectMapper objectMapper) {
        this.courseId = entity.getCourse() == null ? null : entity.getCourse().getId();
        this.studentId = entity.getStudent() == null ? null : entity.getStudent().getId();
        this.analysis = parseAnalysis(objectMapper, entity.getAnalysisJson());
        this.summaryMarkdown = entity.getSummaryMarkdown();
        this.source = entity.getSource();
        this.fallbackUsed = entity.isFallbackUsed();
        this.reason = entity.getFallbackReason();
        this.confidence = entity.getConfidence();
        this.generatedAt = entity.getGeneratedAt();
    }

    private static Map<String, Object> parseAnalysis(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
