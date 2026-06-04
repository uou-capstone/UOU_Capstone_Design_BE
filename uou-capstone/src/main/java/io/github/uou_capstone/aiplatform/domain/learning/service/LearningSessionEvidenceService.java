package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningSessionEvidenceService {

    private final LearningSessionEvidenceRepository evidenceRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveFromStreamLine(String line, Long sessionId, Long lectureId, User currentUser) {
        if (currentUser == null || currentUser.getStudent() == null) {
            return;
        }

        Map<String, Object> evidenceMap = extractLearningEvidence(line);
        if (evidenceMap == null) {
            return;
        }

        String evidenceId = firstNonBlank(
                asString(evidenceMap.get("evidenceId")),
                asString(evidenceMap.get("evidence_id"))
        );
        if (!StringUtils.hasText(evidenceId)) {
            log.warn("Learning evidence skipped without evidenceId: sessionId={}, lectureId={}", sessionId, lectureId);
            return;
        }
        if (evidenceRepository.existsByEvidenceId(evidenceId)) {
            return;
        }

        Long courseId = firstNonNull(asLong(evidenceMap.get("courseId")), asLong(evidenceMap.get("course_id")));
        if (courseId == null) {
            courseId = findCourseIdByLectureId(lectureId);
        }

        Long materialId = firstNonNull(asLong(evidenceMap.get("materialId")), asLong(evidenceMap.get("material_id")));
        Map<?, ?> quiz = asMap(evidenceMap.get("quiz"));
        Map<?, ?> grading = asMap(evidenceMap.get("grading"));
        Map<?, ?> diagnosis = asMap(evidenceMap.get("diagnosis"));

        LocalDateTime occurredAt = parseDateTime(firstNonBlank(
                asString(evidenceMap.get("occurredAt")),
                asString(evidenceMap.get("occurred_at")),
                asString(evidenceMap.get("timestamp")),
                asString(evidenceMap.get("createdAt")),
                asString(evidenceMap.get("created_at"))
        ));

        LearningSessionEvidence evidence = LearningSessionEvidence.builder()
                .evidenceId(evidenceId)
                .course(entityManager.getReference(Course.class, courseId))
                .lecture(entityManager.getReference(Lecture.class, lectureId))
                .material(materialId == null ? null : entityManager.getReference(Material.class, materialId))
                .student(entityManager.getReference(Student.class, currentUser.getStudent().getId()))
                .session(entityManager.getReference(LearningChatSession.class, sessionId))
                .pageNumber(firstNonNull(
                        asInteger(evidenceMap.get("pageNumber")),
                        asInteger(evidenceMap.get("page_number"))
                ))
                .eventType(firstNonBlank(
                        asString(evidenceMap.get("eventType")),
                        asString(evidenceMap.get("event_type")),
                        asString(evidenceMap.get("eventKind"))
                ))
                .quizType(firstNonBlank(
                        asString(evidenceMap.get("quizType")),
                        asString(evidenceMap.get("quiz_type")),
                        asString(quiz.get("quizType")),
                        asString(quiz.get("quiz_type"))
                ))
                .scoreRatio(firstNonNull(
                        asDouble(evidenceMap.get("scoreRatio")),
                        asDouble(evidenceMap.get("score_ratio")),
                        asDouble(grading.get("scoreRatio")),
                        asDouble(grading.get("score_ratio"))
                ))
                .passed(firstNonNull(
                        asBoolean(evidenceMap.get("passed")),
                        asBoolean(grading.get("passed"))
                ))
                .weakConcepts(asStringList(firstNonNull(
                        evidenceMap.get("weakConcepts"),
                        evidenceMap.get("weak_concepts"),
                        diagnosis.get("weakConcepts"),
                        diagnosis.get("weak_concepts")
                )))
                .wrongItems(asObjectList(firstNonNull(
                        evidenceMap.get("wrongItems"),
                        evidenceMap.get("wrong_items"),
                        evidenceMap.get("missedQuestions"),
                        evidenceMap.get("missed_questions"),
                        grading.get("wrongItems"),
                        grading.get("wrong_items"),
                        grading.get("missedQuestions"),
                        grading.get("missed_questions")
                )))
                .evidence(new LinkedHashMap<>(evidenceMap))
                .occurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt)
                .build();

        try {
            evidenceRepository.saveAndFlush(evidence);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate learning evidence ignored: evidenceId={}", evidenceId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractLearningEvidence(String line) {
        Map<String, Object> envelope = readObject(line);
        if (!"done".equals(envelope.get("type"))) {
            return null;
        }
        Object dataRaw = envelope.get("data");
        if (!(dataRaw instanceof Map<?, ?> data)) {
            return null;
        }
        Object evidenceRaw = data.get("learningEvidence");
        if (!(evidenceRaw instanceof Map<?, ?> evidence)
                || !"learning_evidence".equals(evidence.get("type"))) {
            return null;
        }
        return new LinkedHashMap<>((Map<String, Object>) evidence);
    }

    private Map<String, Object> readObject(String line) {
        try {
            return objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("AI stream line skipped for learning session evidence: {}", line);
            return Map.of();
        }
    }

    private Long findCourseIdByLectureId(Long lectureId) {
        return entityManager.createQuery("""
                        SELECT l.course.id
                        FROM Lecture l
                        WHERE l.id = :lectureId
                        """, Long.class)
                .setParameter("lectureId", lectureId)
                .getSingleResult();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String text = asString(item);
            if (StringUtils.hasText(text)) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static List<Object> asObjectList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return List.copyOf(raw);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInteger(Object value) {
        Long v = asLong(value);
        return v == null ? null : v.intValue();
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
