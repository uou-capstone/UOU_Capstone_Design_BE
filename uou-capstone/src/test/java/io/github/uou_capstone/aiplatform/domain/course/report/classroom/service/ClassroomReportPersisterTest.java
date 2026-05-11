package io.github.uou_capstone.aiplatform.domain.course.report.classroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.repository.ClassroomReportRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
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

/**
 * UPSERT 동시성 race 처리 검증.
 * 외부 upsert 는 non-transactional 이고, tryInsertOrUpdate 가 UNIQUE 위반으로 실패하면
 * 새 트랜잭션의 applyUpdate 가 호출되어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ClassroomReportPersisterTest {

    @Mock private ClassroomReportRepository repository;
    @Mock private CourseRepository courseRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClassroomReportPersister persister;
    private ClassroomReportPersister selfMock;

    @BeforeEach
    void setUp() {
        persister = new ClassroomReportPersister(repository, courseRepository, objectMapper);
        // self-injection 은 프로덕션에서 @Autowired @Lazy 로 들어가므로 테스트에서 직접 주입.
        selfMock = mock(ClassroomReportPersister.class);
        ReflectionTestUtils.setField(persister, "self", selfMock);
    }

    @Test
    @DisplayName("정상 경로 — tryInsertOrUpdate 만 호출, applyUpdate 는 호출되지 않음")
    void normalPath_callsTryOnly() {
        Map<String, Object> data = new HashMap<>();
        data.put("summaryMarkdown", "abc");

        persister.upsert(100L, data);

        verify(selfMock).tryInsertOrUpdate(eq(100L), any(ClassroomReportPersister.Snapshot.class));
        verify(selfMock, never()).applyUpdate(anyLong(), any());
    }

    @Test
    @DisplayName("동시 insert race — DataIntegrityViolationException 시 applyUpdate 로 fallback")
    void uniqueViolation_fallsBackToApplyUpdate() {
        Map<String, Object> data = new HashMap<>();
        data.put("summaryMarkdown", "abc");
        data.put("fallbackUsed", false);

        doThrow(new DataIntegrityViolationException("uniq"))
                .when(selfMock).tryInsertOrUpdate(eq(100L), any());
        doNothing().when(selfMock).applyUpdate(eq(100L), any());

        persister.upsert(100L, data);

        verify(selfMock).tryInsertOrUpdate(eq(100L), any(ClassroomReportPersister.Snapshot.class));
        verify(selfMock).applyUpdate(eq(100L), any(ClassroomReportPersister.Snapshot.class));
    }

    @Test
    @DisplayName("null data 면 모든 작업 skip")
    void nullData_noop() {
        persister.upsert(100L, null);

        verifyNoInteractions(selfMock);
    }

    @Test
    @DisplayName("Snapshot extract — highlights/risks/coachingPriorities 는 JSON 직렬화, fallback 필드는 그대로")
    void extractSerializesArraysAndFlags() {
        Map<String, Object> data = new HashMap<>();
        data.put("summaryMarkdown", "summary");
        data.put("highlights", List.of("a", "b"));
        data.put("risks", List.of(Map.of("k", "v")));
        data.put("coachingPriorities", List.of());
        data.put("source", "FALLBACK");
        data.put("fallbackUsed", true);
        data.put("reason", "quota");
        data.put("confidence", "LOW");

        ClassroomReportPersister.Snapshot s = persister.extract(data);

        assertThat(s.summary()).isEqualTo("summary");
        assertThat(s.highlightsJson()).isEqualTo("[\"a\",\"b\"]");
        assertThat(s.risksJson()).isEqualTo("[{\"k\":\"v\"}]");
        assertThat(s.coachingJson()).isEqualTo("[]");
        assertThat(s.source()).isEqualTo("FALLBACK");
        assertThat(s.fallbackUsed()).isTrue();
        assertThat(s.reason()).isEqualTo("quota");
        assertThat(s.confidence()).isEqualTo("LOW");
        assertThat(s.generatedAt()).isNotNull();
    }
}
