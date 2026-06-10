package io.github.uou_capstone.aiplatform.domain.course.report.criteria.controller;

import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.service.CourseReportCriteriaAssistantService;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.service.CourseReportCriterionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseReportCriteriaControllerTest {

    private CourseReportCriterionService criterionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        criterionService = mock(CourseReportCriterionService.class);
        CourseReportCriteriaAssistantService assistantService = mock(CourseReportCriteriaAssistantService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CourseReportCriteriaController(criterionService, assistantService))
                .build();
    }

    @Test
    void listKeepsCustomOnlyNumericIdContract() throws Exception {
        CourseReportCriterion criterion = CourseReportCriterion.builder()
                .label("발표 참여도")
                .description("발표 시도")
                .weight(20)
                .build();
        ReflectionTestUtils.setField(criterion, "id", 11L);

        when(criterionService.list(1L)).thenReturn(List.of(new CriterionResponse(criterion)));

        mockMvc.perform(get("/api/courses/1/reports/criteria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].label").value("발표 참여도"))
                .andExpect(jsonPath("$[0].builtIn").doesNotExist())
                .andExpect(jsonPath("$[0].criterionId").doesNotExist());
    }

    @Test
    void listAllReturnsBuiltInsAndCustomCriteriaContract() throws Exception {
        when(criterionService.listAll(1L)).thenReturn(List.of(
                ReportCriterionResponse.builder()
                        .id("builtin:CONCEPT_UNDERSTANDING")
                        .key("CONCEPT_UNDERSTANDING")
                        .label("개념 이해도")
                        .description("핵심 개념 이해")
                        .builtIn(true)
                        .editable(false)
                        .deletable(false)
                        .build(),
                ReportCriterionResponse.builder()
                        .id("custom:11")
                        .key("custom:11")
                        .criterionId(11L)
                        .label("발표 참여도")
                        .description("발표 시도")
                        .weight(20)
                        .builtIn(false)
                        .editable(true)
                        .deletable(true)
                        .build()));

        mockMvc.perform(get("/api/courses/1/reports/criteria/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("builtin:CONCEPT_UNDERSTANDING"))
                .andExpect(jsonPath("$[0].criterionId").doesNotExist())
                .andExpect(jsonPath("$[0].builtIn").value(true))
                .andExpect(jsonPath("$[0].editable").value(false))
                .andExpect(jsonPath("$[0].deletable").value(false))
                .andExpect(jsonPath("$[1].id").value("custom:11"))
                .andExpect(jsonPath("$[1].criterionId").value(11))
                .andExpect(jsonPath("$[1].builtIn").value(false))
                .andExpect(jsonPath("$[1].editable").value(true))
                .andExpect(jsonPath("$[1].deletable").value(true));
    }
}
