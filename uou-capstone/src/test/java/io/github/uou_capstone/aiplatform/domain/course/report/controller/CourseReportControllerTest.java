package io.github.uou_capstone.aiplatform.domain.course.report.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.service.ClassroomReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseReportSupplementService;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.entity.StudentReportAnalysis;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatPersistenceService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentchat.service.StudentReportChatService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseReportControllerTest {

    private StudentReportAnalysisService studentReportAnalysisService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CourseStudentReportService courseStudentReportService = mock(CourseStudentReportService.class);
        CourseReportSupplementService courseReportSupplementService = mock(CourseReportSupplementService.class);
        ClassroomReportService classroomReportService = mock(ClassroomReportService.class);
        StudentReportChatService studentReportChatService = mock(StudentReportChatService.class);
        StudentReportChatPersistenceService studentReportChatPersistenceService =
                mock(StudentReportChatPersistenceService.class);
        studentReportAnalysisService = mock(StudentReportAnalysisService.class);

        mockMvc = MockMvcBuilders.standaloneSetup(new CourseReportController(
                courseStudentReportService,
                courseReportSupplementService,
                classroomReportService,
                studentReportChatService,
                studentReportChatPersistenceService,
                studentReportAnalysisService))
                .build();
    }

    @Test
    void getStudentAnalysisReturnsNoContentWhenMissing() throws Exception {
        when(studentReportAnalysisService.get(1L, 2L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/courses/1/reports/students/2/analysis"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getStudentAnalysisReturnsSavedResult() throws Exception {
        Course course = Course.builder().title("c").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", 1L);
        Student student = Student.builder().grade(1).classNumber("1").build();
        ReflectionTestUtils.setField(student, "id", 2L);
        StudentReportAnalysis analysis = StudentReportAnalysis.builder()
                .course(course)
                .student(student)
                .analysisJson("""
                        {
                          "summaryMarkdown": "summary",
                          "dataCoverage": {
                            "evidenceCount": 3,
                            "gradableEvidenceCount": 2,
                            "quizAttemptCount": 1,
                            "confidence": "MEDIUM"
                          },
                          "quantitativeMetrics": [
                            {
                              "type": "DATA_COVERAGE",
                              "label": "Data coverage",
                              "score": null,
                              "description": "Evidence readiness"
                            }
                          ],
                          "initialSignalScore": null,
                          "competencyAnalysis": [
                            {
                              "criterionId": "builtin:CONCEPT_UNDERSTANDING",
                              "score": null,
                              "insufficientEvidence": true,
                              "evidenceRefs": ["evidence-1"]
                            }
                          ]
                        }
                        """)
                .summaryMarkdown("summary")
                .fallbackUsed(false)
                .build();

        when(studentReportAnalysisService.get(1L, 2L))
                .thenReturn(Optional.of(new StudentReportAnalysisResponse(analysis, new ObjectMapper())));

        mockMvc.perform(get("/api/courses/1/reports/students/2/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.studentId").value(2))
                .andExpect(jsonPath("$.summaryMarkdown").value("summary"))
                .andExpect(jsonPath("$.analysis.summaryMarkdown").value("summary"))
                .andExpect(jsonPath("$.dataCoverage.evidenceCount").value(3))
                .andExpect(jsonPath("$.analysis.dataCoverage.evidenceCount").value(3))
                .andExpect(jsonPath("$.quantitativeMetrics[0].type").value("DATA_COVERAGE"))
                .andExpect(jsonPath("$.quantitativeMetrics[0].score").value(nullValue()))
                .andExpect(jsonPath("$.analysis.quantitativeMetrics[0].score").value(nullValue()))
                .andExpect(jsonPath("$.initialSignalScore").value(nullValue()))
                .andExpect(jsonPath("$.competencyAnalysis[0].criterionId").value("builtin:CONCEPT_UNDERSTANDING"))
                .andExpect(jsonPath("$.competencyAnalysis[0].insufficientEvidence").value(true))
                .andExpect(jsonPath("$.competencyAnalysis[0].evidenceRefs[0]").value("evidence-1"));
    }

    @Test
    void streamStudentAnalysisUsesSseHeaders() throws Exception {
        when(studentReportAnalysisService.analyzeStream(eq(1L), eq(2L), any()))
                .thenReturn(Flux.just(ServerSentEvent.builder(Map.<String, Object>of("type", "done"))
                        .event("done")
                        .build()));

        mockMvc.perform(post("/api/courses/1/reports/students/2/analyze/stream")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
