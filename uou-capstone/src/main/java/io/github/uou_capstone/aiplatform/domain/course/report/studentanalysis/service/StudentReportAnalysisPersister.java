package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentReportAnalysisPersister {

    private final StudentReportAnalysisRepository repository;
    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private StudentReportAnalysisPersister self;

    public void upsert(Long courseId, Long studentId, Map<String, Object> data) {
        if (data == null) return;
        Snapshot snapshot = extract(data);

        try {
            self.tryInsertOrUpdate(courseId, studentId, snapshot);
        } catch (DataIntegrityViolationException e) {
            log.warn("Student report analysis concurrent insert detected, reloading for update: courseId={}, studentId={}",
                    courseId, studentId);
            self.applyUpdate(courseId, studentId, snapshot);
        }
    }

    @Transactional
    public void tryInsertOrUpdate(Long courseId, Long studentId, Snapshot snapshot) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        repository.findByCourseAndStudent(course, student)
                .ifPresentOrElse(existing -> apply(existing, snapshot),
                        () -> repository.save(StudentReportAnalysis.builder()
                                .course(course)
                                .student(student)
                                .analysisJson(snapshot.analysisJson())
                                .summaryMarkdown(snapshot.summaryMarkdown())
                                .source(snapshot.source())
                                .fallbackUsed(snapshot.fallbackUsed())
                                .fallbackReason(snapshot.reason())
                                .confidence(snapshot.confidence())
                                .generatedAt(snapshot.generatedAt())
                                .build()));
    }

    @Transactional
    public void applyUpdate(Long courseId, Long studentId, Snapshot snapshot) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        StudentReportAnalysis latest = repository.findByCourseAndStudent(course, student)
                .orElseThrow(() -> new IllegalStateException(
                        "Student report analysis UPSERT race recovery failed: courseId=" + courseId
                                + ", studentId=" + studentId));
        apply(latest, snapshot);
    }

    Snapshot extract(Map<String, Object> data) {
        Map<String, Object> normalized = normalizeSummary(data);
        return new Snapshot(
                serializeObject(normalized),
                stringOrNull(normalized, "summaryMarkdown"),
                stringOrNull(normalized, "source"),
                Boolean.TRUE.equals(normalized.get("fallbackUsed")),
                stringOrNull(normalized, "reason"),
                stringOrNull(normalized, "confidence"),
                LocalDateTime.now()
        );
    }

    private void apply(StudentReportAnalysis analysis, Snapshot snapshot) {
        analysis.update(snapshot.analysisJson(), snapshot.summaryMarkdown(), snapshot.source(),
                snapshot.fallbackUsed(), snapshot.reason(), snapshot.confidence(), snapshot.generatedAt());
    }

    private String serializeObject(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR,
                    "Student report analysis JSON serialization failed.");
        }
    }

    private String stringOrNull(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Map<String, Object> normalizeSummary(Map<String, Object> data) {
        if (data == null || data.get("summaryMarkdown") != null || data.get("summary") == null) {
            return data;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(data);
        normalized.put("summaryMarkdown", String.valueOf(data.get("summary")));
        return normalized;
    }

    public record Snapshot(String analysisJson,
                           String summaryMarkdown,
                           String source,
                           boolean fallbackUsed,
                           String reason,
                           String confidence,
                           LocalDateTime generatedAt) {}
}
