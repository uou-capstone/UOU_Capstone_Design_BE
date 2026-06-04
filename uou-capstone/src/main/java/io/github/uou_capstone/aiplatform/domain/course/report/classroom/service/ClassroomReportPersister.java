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
 * <p>동시 호출 시 race 처리: {@code classroom_reports.course_id} UNIQUE 제약을 이용한다.
 * <ul>
 *   <li>{@link #upsert} — <b>non-transactional</b>. 트랜잭션 경계는 {@link #tryInsertOrUpdate} /
 *       {@link #applyUpdate} 안에서 시작/종료된다.</li>
 *   <li>{@link #tryInsertOrUpdate} 의 트랜잭션이 commit 단계에서 UNIQUE 위반으로 실패하면
 *       {@link DataIntegrityViolationException} 이 outer 로 propagate 된다. 이 시점에 해당
 *       트랜잭션은 이미 rollback 완료 상태이므로 catch 가 안전하다.</li>
 *   <li>catch 후 {@link #applyUpdate} 가 새 트랜잭션에서 reload + update 수행.</li>
 * </ul>
 *
 * <p>이전 디자인(상위 메서드가 @Transactional + saveAndFlush 후 같은 트랜잭션 내에서 catch)은
 * Hibernate persistence context 가 invalid 상태로 마킹되어 outer commit 시 또 다른 예외가
 * 발생할 위험이 있었다. 본 디자인은 트랜잭션 경계를 catch 밖으로 끌어내 그 위험을 제거한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassroomReportPersister {

    private final ClassroomReportRepository repository;
    private final CourseRepository courseRepository;
    private final ObjectMapper objectMapper;

    /**
     * Self-injection — race 복구 시 같은 빈의 {@link #applyUpdate} 를 호출하기 위함.
     * Spring proxy 를 통과해야 {@code @Transactional} 이 동작한다.
     * {@code @Lazy} 가 없으면 circular dependency 로 부팅 실패.
     */
    @Autowired
    @Lazy
    private ClassroomReportPersister self;

    /**
     * 강의실 리포트 UPSERT 진입점. <b>호출자 트랜잭션이 없는 상태에서만 호출해야 한다.</b>
     * ({@link ClassroomReportService} 의 동기/스트림 경로는 모두 non-transactional)
     */
    public void upsert(Long courseId, Map<String, Object> data) {
        if (data == null) return;
        Snapshot s = extract(data);

        try {
            self.tryInsertOrUpdate(courseId, s);
        } catch (DataIntegrityViolationException e) {
            log.warn("Classroom report 동시 insert 감지 — 새 트랜잭션으로 reload+update: courseId={}", courseId);
            self.applyUpdate(courseId, s);
        }
    }

    /**
     * 트랜잭션 1차 시도: row 가 있으면 update, 없으면 insert.
     * 동시 insert 시 commit 단계에서 {@link DataIntegrityViolationException} 가 outer 로 propagate.
     */
    @Transactional
    public void tryInsertOrUpdate(Long courseId, Snapshot s) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        Optional<ClassroomReport> existing = repository.findByCourse(course);
        if (existing.isPresent()) {
            existing.get().update(s.summary, s.highlightsJson, s.risksJson, s.coachingJson,
                    s.source, s.fallbackUsed, s.reason, s.confidence, s.generatedAt);
            return;
        }
        repository.save(ClassroomReport.builder()
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
    }

    /**
     * race 복구 — {@link #tryInsertOrUpdate} 가 UNIQUE 위반으로 실패한 후 새 트랜잭션에서 reload + update.
     * 직접 호출하지 말 것 (race 복구 전용).
     */
    @Transactional
    public void applyUpdate(Long courseId, Snapshot s) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        ClassroomReport latest = repository.findByCourse(course)
                .orElseThrow(() -> new IllegalStateException(
                        "Classroom report UPSERT race recovery 실패 — uniq 위반 후 row 미존재: courseId=" + courseId));
        latest.update(s.summary, s.highlightsJson, s.risksJson, s.coachingJson,
                s.source, s.fallbackUsed, s.reason, s.confidence, s.generatedAt);
    }

    Snapshot extract(Map<String, Object> data) {
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
