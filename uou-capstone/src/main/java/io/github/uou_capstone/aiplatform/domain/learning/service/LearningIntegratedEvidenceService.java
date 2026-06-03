package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningIntegratedEvidence;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningIntegratedEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningIntegratedEvidenceService {

    private final LearningIntegratedEvidenceRepository evidenceRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveFromStreamLine(String line, Long sessionId, Long lectureId, User currentUser) {
        if (currentUser == null || currentUser.getStudent() == null) {
            return;
        }
        Map<String, Object> learningEvidence = extractLearningEvidence(line);
        if (learningEvidence == null) {
            return;
        }

        Map<String, Object> envelope = readObject(line);
        Object dataRaw = envelope.get("data");
        Map<?, ?> data = dataRaw instanceof Map<?, ?> dataMap ? dataMap : Map.of();
        String eventKind = firstNonBlank(asString(data.get("eventKind")), asString(learningEvidence.get("eventKind")));

        Long materialId = asLong(learningEvidence.get("materialId"));
        LearningIntegratedEvidence evidence = LearningIntegratedEvidence.builder()
                .chatSession(entityManager.getReference(LearningChatSession.class, sessionId))
                .user(entityManager.getReference(User.class, currentUser.getId()))
                .lecture(entityManager.getReference(Lecture.class, lectureId))
                .material(materialId == null ? null : entityManager.getReference(Material.class, materialId))
                .eventKind(eventKind)
                .pageNumber(asInteger(learningEvidence.get("pageNumber")))
                .coverageStartPage(asInteger(learningEvidence.get("coverageStartPage")))
                .coverageEndPage(asInteger(learningEvidence.get("coverageEndPage")))
                .quizId(asString(learningEvidence.get("quizId")))
                .quizType(asString(learningEvidence.get("quizType")))
                .scoreRatio(asDouble(learningEvidence.get("scoreRatio")))
                .passed(asBoolean(learningEvidence.get("passed")))
                .weakConcepts(asStringList(learningEvidence.get("weakConcepts")))
                .missedQuestions(asObjectList(learningEvidence.get("missedQuestions")))
                .diagnosticPrompt(asString(learningEvidence.get("diagnosticPrompt")))
                .rawEvidence(new LinkedHashMap<>(learningEvidence))
                .build();

        evidenceRepository.save(evidence);
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
        if (!(evidenceRaw instanceof Map<?, ?> evidence)) {
            return null;
        }
        return new LinkedHashMap<>((Map<String, Object>) evidence);
    }

    private Map<String, Object> readObject(String line) {
        try {
            return objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("AI stream line skipped for learning evidence: {}", line);
            return Map.of();
        }
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

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
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
