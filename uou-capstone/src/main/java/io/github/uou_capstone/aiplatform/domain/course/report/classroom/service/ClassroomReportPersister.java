package io.github.uou_capstone.aiplatform.domain.course.report.classroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.entity.ClassroomReport;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.repository.ClassroomReportRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Classroom 리포트 UPSERT 전담 빈.
 *
 * <p>{@link ClassroomReportService}의 동기/스트림 경로 양쪽에서 호출되며, self-call 시 {@code @Transactional}
 * 이 동작하도록 별도 빈으로 분리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassroomReportPersister {

    private final ClassroomReportRepository repository;
    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void upsert(Long courseId, Map<String, Object> data) {
        if (data == null) return;
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        Optional<ClassroomReport> existing = repository.findByCourse(course);

        String summary = stringOrNull(data, "summaryMarkdown");
        String highlightsJson = serializeArray(data.get("highlights"));
        String risksJson = serializeArray(data.get("risks"));
        String coachingJson = serializeArray(data.get("coachingPriorities"));
        String source = stringOrNull(data, "source");
        boolean fallbackUsed = Boolean.TRUE.equals(data.get("fallbackUsed"));
        String reason = stringOrNull(data, "reason");
        String confidence = stringOrNull(data, "confidence");
        LocalDateTime generatedAt = LocalDateTime.now();

        if (existing.isPresent()) {
            existing.get().update(summary, highlightsJson, risksJson, coachingJson,
                    source, fallbackUsed, reason, confidence, generatedAt);
        } else {
            repository.save(ClassroomReport.builder()
                    .course(course)
                    .summaryMarkdown(summary)
                    .highlightsJson(highlightsJson)
                    .risksJson(risksJson)
                    .coachingPrioritiesJson(coachingJson)
                    .source(source)
                    .fallbackUsed(fallbackUsed)
                    .fallbackReason(reason)
                    .confidence(confidence)
                    .generatedAt(generatedAt)
                    .build());
        }
    }

    private String stringOrNull(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String serializeArray(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Classroom report JSON 직렬화 실패, key payload={}", value, e);
            return null;
        }
    }
}
