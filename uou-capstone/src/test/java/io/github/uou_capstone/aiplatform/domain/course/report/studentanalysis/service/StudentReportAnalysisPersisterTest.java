package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StudentReportAnalysisPersisterTest {

    @Mock private StudentReportAnalysisRepository repository;
    @Mock private CourseRepository courseRepository;
    @Mock private StudentRepository studentRepository;

    private StudentReportAnalysisPersister persister;
    private StudentReportAnalysisPersister selfMock;

    @BeforeEach
    void setUp() {
        persister = new StudentReportAnalysisPersister(
                repository, courseRepository, studentRepository, new ObjectMapper());
        selfMock = mock(StudentReportAnalysisPersister.class);
        ReflectionTestUtils.setField(persister, "self", selfMock);
    }

    @Test
    void normalPathCallsTryOnly() {
        Map<String, Object> data = Map.of("summaryMarkdown", "summary");

        persister.upsert(1L, 2L, data);

        verify(selfMock).tryInsertOrUpdate(eq(1L), eq(2L), any(StudentReportAnalysisPersister.Snapshot.class));
        verify(selfMock, never()).applyUpdate(anyLong(), anyLong(), any());
    }

    @Test
    void uniqueViolationFallsBackToApplyUpdate() {
        Map<String, Object> data = Map.of("summaryMarkdown", "summary");
        doThrow(new DataIntegrityViolationException("uniq"))
                .when(selfMock).tryInsertOrUpdate(eq(1L), eq(2L), any());
        doNothing().when(selfMock).applyUpdate(eq(1L), eq(2L), any());

        persister.upsert(1L, 2L, data);

        verify(selfMock).tryInsertOrUpdate(eq(1L), eq(2L), any(StudentReportAnalysisPersister.Snapshot.class));
        verify(selfMock).applyUpdate(eq(1L), eq(2L), any(StudentReportAnalysisPersister.Snapshot.class));
    }

    @Test
    void nullDataNoop() {
        persister.upsert(1L, 2L, null);

        verifyNoInteractions(selfMock);
    }

    @Test
    void extractSerializesRawJsonAndMetadata() {
        Map<String, Object> data = Map.of(
                "summaryMarkdown", "summary",
                "source", "GEMINI",
                "fallbackUsed", true,
                "reason", "ok",
                "confidence", "LOW",
                "competencyAnalysis", List.of(Map.of("label", "개념 이해도")));

        StudentReportAnalysisPersister.Snapshot snapshot = persister.extract(data);

        assertThat(snapshot.analysisJson()).contains("\"summaryMarkdown\":\"summary\"");
        assertThat(snapshot.analysisJson()).contains("\"competencyAnalysis\":[");
        assertThat(snapshot.summaryMarkdown()).isEqualTo("summary");
        assertThat(snapshot.source()).isEqualTo("GEMINI");
        assertThat(snapshot.fallbackUsed()).isTrue();
        assertThat(snapshot.reason()).isEqualTo("ok");
        assertThat(snapshot.confidence()).isEqualTo("LOW");
        assertThat(snapshot.generatedAt()).isNotNull();
    }
}
