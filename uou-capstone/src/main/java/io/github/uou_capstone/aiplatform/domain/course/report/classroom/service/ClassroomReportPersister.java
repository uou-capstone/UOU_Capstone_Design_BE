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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Classroom 리포트 UPSERT 전담 빈.
 *
 * <p>{@link ClassroomReportService}의 동기/스트림 경로 양쪽에서 호출되며, self-call 시 {@code @Transactional}
 * 이 동작하도록 별도 빈으로 분리.
 *
 * <p>동시 호출 시 race 처리: {@code classroom_reports.course_id} UNIQUE 제약을 이용해
 * INSERT 실패 시 {@link DataIntegrityViolationException} 을 catch 한 뒤 별도 트랜잭션
 * ({@link Propagation#REQUIRES_NEW}) 으로 reload + update.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassroomReportPersister {

    private final ClassroomReportRepository repository;
    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;

    /**
     * Self-injection — race 복구 시 같은 빈의 {@link #applyUpdate} 를 새 트랜잭션으로 호출하기 위함.
     * {@code @Lazy} 가 없으면 circular dependency 로 부팅 실패.
     */
    @Autowired
    @Lazy
    private ClassroomReportPersister self;

    @Transactional
    public void upsert(Long courseId, Map<String, Object> data) {
        if (data == null) return;
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));

        Snapshot s = extract(data);

        Optional<ClassroomReport> existing = repository.findByCourse(course);
        if (existing.isPresent()) {
            existing.get().update(s.summary, s.highlightsJson, s.risksJson, s.coachingJson,
                    s.source, s.fallbackUsed, s.reason, s.confidence, s.generatedAt);
            return;
        }

        try {
            repository.saveAndFlush(ClassroomReport.builder()
                    .course(course)
                    .summaryMarkdown(s.summary)
                    .highlightsJson(s.highlightsJson)
                    .risksJson(s.risksJson)
                    .coachingPrioritiesJson(s.coachingJson)
                    .source(s.source)
                    .fallbackUsed(s.fallbackUsed)
                    .fallbackReason(s.reason)
                    .confidence(s.confidence)
                    .generatedAt(s.generatedAt)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.warn("Classroom report 동시 insert 감지 — REQUIRES_NEW 로 재시도: courseId={}", courseId);
            self.applyUpdate(courseId, s);
        }
    }

    /**
     * 동시 insert race 후 fallback — 새 트랜잭션에서 reload + update.
     * 직접 호출하지 말 것 (race 복구 전용).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyUpdate(Long courseId, Snapshot s) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        ClassroomReport latest = repository.findByCourse(course)
                .orElseThrow(() -> new IllegalStateException(
                        "Classroom report UPSERT race recovery 실패 — uniq 위반 후 row 미존재: courseId=" + courseId));
        latest.update(s.summary, s.highlightsJson, s.risksJson, s.coachingJson,
                s.source, s.fallbackUsed, s.reason, s.confidence, s.generatedAt);
    }

    private Snapshot extract(Map<String, Object> data) {
        return new Snapshot(
                stringOrNull(data, "summaryMarkdown"),
                serializeArray(data.get("highlights")),
                serializeArray(data.get("risks")),
                serializeArray(data.get("coachingPriorities")),
                stringOrNull(data, "source"),
                Boolean.TRUE.equals(data.get("fallbackUsed")),
                stringOrNull(data, "reason"),
                stringOrNull(data, "confidence"),
                LocalDateTime.now()
        );
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

    public record Snapshot(String summary,
                            String highlightsJson,
                            String risksJson,
                            String coachingJson,
                            String source,
                            boolean fallbackUsed,
                            String reason,
                            String confidence,
                            LocalDateTime generatedAt) {}
}
