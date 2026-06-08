package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantChatRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseReportCriteriaAssistantServiceTest {

    @Mock private CourseAccessService courseAccessService;
    @Mock private CourseReportCriterionRepository criterionRepository;
    @Mock private CourseReportCriteriaCatalog catalog;
    @Mock private FastApiBridgeClient fastApiBridgeClient;

    private CourseReportCriteriaAssistantService service;

    @BeforeEach
    void setUp() {
        service = new CourseReportCriteriaAssistantService(
                courseAccessService, criterionRepository, catalog, fastApiBridgeClient, new ObjectMapper());
    }

    @Test
    @DisplayName("criteria chat stream forwards current course criteria as additionalCriteria")
    void streamChatBuildsFastApiBody() {
        Course course = Course.builder()
                .title("수학")
                .description("desc")
                .invitationCode("ABC123")
                .build();
        ReflectionTestUtils.setField(course, "id", 1L);
        CourseReportCriterion criterion = CourseReportCriterion.builder()
                .course(course)
                .label("개념 이해도")
                .description("개념을 설명할 수 있는지")
                .weight(40)
                .build();
        ReflectionTestUtils.setField(criterion, "id", 10L);
        CriteriaAssistantChatRequest req = new CriteriaAssistantChatRequest();
        req.setMessage("개념 이해도 기준을 더 구체화해줘");
        req.setHistory(List.of(Map.of("role", "user", "content", "이전 요청")));
        req.setCurrentProposal(Map.of("criterion", Map.of("name", "초안")));
        req.setModel("gemini-test");
        req.setResponseJsonSchema(Map.of("type", "object"));

        when(courseAccessService.loadCourseAsTeacher(1L)).thenReturn(course);
        when(criterionRepository.findByCourseOrderByIdAsc(course)).thenReturn(List.of(criterion));
        when(catalog.builtInCriteria()).thenReturn(List.of(builtInCriterion()));
        when(fastApiBridgeClient.reportCriteriaAssistantChatStream(any())).thenReturn(Flux.empty());

        service.streamChat(1L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).reportCriteriaAssistantChatStream(captor.capture());
        Map<String, Object> body = captor.getValue();

        assertThat(body).containsEntry("courseId", 1L);
        assertThat(body).containsEntry("courseName", "수학");
        assertThat(body).containsEntry("message", "개념 이해도 기준을 더 구체화해줘");
        assertThat(body).containsEntry("model", "gemini-test");
        assertThat(body).containsEntry("history", req.getHistory());
        assertThat(body).containsEntry("currentProposal", req.getCurrentProposal());
        assertThat(body).containsEntry("responseJsonSchema", req.getResponseJsonSchema());
        assertThat(body).doesNotContainKey("existingCriteria");
        assertThat((List<?>) body.get("builtInCriteria")).hasSize(1);
        assertThat((List<?>) body.get("additionalCriteria")).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> builtIn = (Map<String, Object>) ((List<?>) body.get("builtInCriteria")).get(0);
        assertThat(builtIn).containsEntry("id", "builtin:CONCEPT_UNDERSTANDING");
        assertThat(builtIn).containsEntry("key", "CONCEPT_UNDERSTANDING");
        assertThat(builtIn).containsEntry("builtIn", true);
        assertThat(builtIn).containsEntry("isBuiltIn", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> additional = (Map<String, Object>) ((List<?>) body.get("additionalCriteria")).get(0);
        assertThat(additional).containsEntry("id", 10L);
        assertThat(additional).containsEntry("name", "개념 이해도");
        assertThat(additional).containsEntry("label", "개념 이해도");
        assertThat(additional).containsEntry("description", "개념을 설명할 수 있는지");
        assertThat(additional).containsEntry("weight", 40);
        assertThat(additional).containsEntry("builtIn", false);
        assertThat(additional).containsEntry("isBuiltIn", false);
    }

    @Test
    @DisplayName("criteria suggestion stream forwards built-ins and custom criteria as existingCriteria")
    void streamAssistantBuildsExistingCriteriaWithBuiltIns() {
        Course course = Course.builder()
                .title("?섑븰")
                .description("desc")
                .invitationCode("ABC123")
                .build();
        ReflectionTestUtils.setField(course, "id", 1L);
        CourseReportCriterion criterion = CourseReportCriterion.builder()
                .course(course)
                .label("발표 참여도")
                .description("발표 시도")
                .weight(20)
                .build();
        ReflectionTestUtils.setField(criterion, "id", 11L);

        CriteriaAssistantRequest req = new CriteriaAssistantRequest();
        req.setDesiredCount(2);

        when(courseAccessService.loadCourseAsTeacher(1L)).thenReturn(course);
        when(criterionRepository.findByCourseOrderByIdAsc(course)).thenReturn(List.of(criterion));
        when(catalog.builtInCriteria()).thenReturn(List.of(builtInCriterion()));
        when(fastApiBridgeClient.reportCriteriaAssistantStream(any())).thenReturn(Flux.empty());

        service.streamAssistant(1L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).reportCriteriaAssistantStream(captor.capture());
        Map<String, Object> body = captor.getValue();

        assertThat((List<?>) body.get("existingCriteria")).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) body.get("existingCriteria")).get(0);
        assertThat(first).containsEntry("id", "builtin:CONCEPT_UNDERSTANDING");
        assertThat(first).containsEntry("builtIn", true);
        assertThat(first).containsEntry("isBuiltIn", true);
    }

    private ReportCriterionResponse builtInCriterion() {
        return ReportCriterionResponse.builder()
                .id("builtin:CONCEPT_UNDERSTANDING")
                .key("CONCEPT_UNDERSTANDING")
                .label("개념 이해도")
                .description("핵심 개념 이해")
                .builtIn(true)
                .editable(false)
                .deletable(false)
                .fallbackPolicy("INSUFFICIENT_EVIDENCE")
                .build();
    }
}
