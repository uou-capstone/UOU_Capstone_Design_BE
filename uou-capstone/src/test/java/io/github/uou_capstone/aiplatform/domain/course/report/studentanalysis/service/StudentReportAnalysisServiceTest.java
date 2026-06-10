package io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.ai.StudentAiReportContextResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.dto.StudentReportAnalysisRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.repository.StudentReportAnalysisRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentReportAnalysisServiceTest {

    private static final long COURSE_ID = 1L;
    private static final long STUDENT_ID = 2L;

    @Mock private CourseAccessService courseAccessService;
    @Mock private CourseStudentReportService courseStudentReportService;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private StudentReportAnalysisRepository analysisRepository;
    @Mock private StudentReportAnalysisPersister persister;
    @Mock private FastApiBridgeClient fastApiBridgeClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StudentReportAnalysisService service;
    private Course course;
    private Student student;

    @BeforeEach
    void setUp() {
        service = new StudentReportAnalysisService(
                courseAccessService,
                courseStudentReportService,
                enrollmentRepository,
                analysisRepository,
                persister,
                fastApiBridgeClient,
                objectMapper);
        course = Course.builder().title("course").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);
        student = Student.builder().grade(1).classNumber("1").build();
        ReflectionTestUtils.setField(student, "id", STUDENT_ID);
    }

    private String fastApiAnalysisJson(String summary) {
        return """
                {
                  "summary": "%s",
                  "strengths": ["concept"],
                  "weaknesses": ["proof"],
                  "competencyAnalysis": [
                    {"criterionId":"builtin:CONCEPT_UNDERSTANDING","score":0.8,"evidence":["quiz"]}
                  ],
                  "teachingSuggestions": ["review"],
                  "followUpQuestions": ["why?"],
                  "confidence": "HIGH"
                }
                """.formatted(summary);
    }

    @Test
    void analyzeSendsSpringBuiltContextAndModelOnly() {
        StudentAiReportContextResponse context = StudentAiReportContextResponse.builder()
                .reportCriteria(List.of(ReportCriterionResponse.builder()
                        .id("builtin:CONCEPT_UNDERSTANDING")
                        .label("Concept understanding")
                        .builtIn(true)
                        .build()))
                .build();
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID)).thenReturn(context);
        when(fastApiBridgeClient.reportStudentAnalyze(any())).thenReturn(fastApiAnalysisJson("ok"));

        StudentReportAnalysisRequest request = new StudentReportAnalysisRequest();
        request.setModel("gemini-2.5-flash");

        Map<String, Object> result = service.analyze(COURSE_ID, STUDENT_ID, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).reportStudentAnalyze(bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body).containsEntry("context", context);
        assertThat(body).containsEntry("model", "gemini-2.5-flash");
        assertThat(body).doesNotContainKey("criteria");
        assertThat(body).doesNotContainKey("reportCriteria");
        assertThat(result).containsEntry("summary", "ok")
                .containsEntry("summaryMarkdown", "ok")
                .containsEntry("confidence", "HIGH");
        assertThat(result.get("competencyAnalysis")).isInstanceOf(List.class);
        verify(persister).upsert(eq(COURSE_ID), eq(STUDENT_ID), eq(result));
    }

    @Test
    void analyzeOmitsDisallowedModel() {
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .thenReturn(StudentAiReportContextResponse.builder().build());
        when(fastApiBridgeClient.reportStudentAnalyze(any())).thenReturn(fastApiAnalysisJson("ok"));

        StudentReportAnalysisRequest request = new StudentReportAnalysisRequest();
        request.setModel("unexpected-model");

        service.analyze(COURSE_ID, STUDENT_ID, request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).reportStudentAnalyze(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).doesNotContainKey("model");
    }

    @Test
    void analyzeInvalidJsonThrowsAiServerErrorAndDoesNotPersist() {
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .thenReturn(StudentAiReportContextResponse.builder().build());
        when(fastApiBridgeClient.reportStudentAnalyze(any())).thenReturn("not-json");

        assertThatThrownBy(() -> service.analyze(COURSE_ID, STUDENT_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.AI_SERVER_ERROR);

        verify(persister, never()).upsert(any(), any(), any());
    }

    @Test
    void streamPersistsDoneDataMapOnly() {
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .thenReturn(StudentAiReportContextResponse.builder().build());
        when(fastApiBridgeClient.reportStudentAnalyzeStream(any())).thenReturn(Flux.just(
                "{\"type\":\"agent_delta\",\"delta\":\"working\"}",
                """
                {"type":"done","data":{"summary":"done","strengths":["s"],"weaknesses":["w"],"competencyAnalysis":[{"score":0.7}],"teachingSuggestions":["t"],"followUpQuestions":["q"],"confidence":"HIGH"}}
                """.trim()));

        List<ServerSentEvent<Map<String, Object>>> events =
                service.analyzeStream(COURSE_ID, STUDENT_ID, null).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event).containsExactly("agent_delta", "done");
        @SuppressWarnings("unchecked")
        Map<String, Object> emittedData = (Map<String, Object>) events.get(1).data().get("data");
        assertThat(emittedData).containsEntry("summary", "done")
                .containsEntry("summaryMarkdown", "done")
                .containsEntry("confidence", "HIGH");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(persister).upsert(eq(COURSE_ID), eq(STUDENT_ID), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("summary", "done")
                .containsEntry("summaryMarkdown", "done")
                .containsEntry("confidence", "HIGH");
    }

    @Test
    void streamDoesNotPersistWhenDoneDataIsNotObject() {
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .thenReturn(StudentAiReportContextResponse.builder().build());
        when(fastApiBridgeClient.reportStudentAnalyzeStream(any())).thenReturn(Flux.just(
                "{\"type\":\"done\",\"data\":\"not-object\"}"));

        service.analyzeStream(COURSE_ID, STUDENT_ID, null).collectList().block();

        verify(persister, never()).upsert(any(), any(), any());
    }

    @Test
    void streamDoesNotPersistWhenDoneEventIsMissing() {
        when(courseStudentReportService.getStudentAiReportContext(COURSE_ID, STUDENT_ID))
                .thenReturn(StudentAiReportContextResponse.builder().build());
        when(fastApiBridgeClient.reportStudentAnalyzeStream(any())).thenReturn(Flux.just(
                "{\"type\":\"agent_delta\",\"delta\":\"working\"}"));

        service.analyzeStream(COURSE_ID, STUDENT_ID, null).collectList().block();

        verify(persister, never()).upsert(any(), any(), any());
    }

    @Test
    void getValidatesTeacherAndEnrollment() {
        Enrollment enrollment = Enrollment.builder().course(course).student(student).build();
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(enrollmentRepository.findByCourseIdAndStudentIdWithUser(COURSE_ID, STUDENT_ID))
                .thenReturn(Optional.of(enrollment));
        when(analysisRepository.findByCourseAndStudent(course, student)).thenReturn(Optional.empty());

        assertThat(service.get(COURSE_ID, STUDENT_ID)).isEmpty();

        verify(courseAccessService).loadCourseAsTeacher(COURSE_ID);
        verify(enrollmentRepository).findByCourseIdAndStudentIdWithUser(COURSE_ID, STUDENT_ID);
    }

    @Test
    void getThrowsMemberNotFoundWhenStudentNotEnrolled() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(enrollmentRepository.findByCourseIdAndStudentIdWithUser(COURSE_ID, STUDENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(COURSE_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    void getPropagatesForbiddenForNonOwnerTeacher() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(courseAccessService).loadCourseAsTeacher(COURSE_ID);

        assertThatThrownBy(() -> service.get(COURSE_ID, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(enrollmentRepository, never()).findByCourseIdAndStudentIdWithUser(any(), any());
        verify(analysisRepository, never()).findByCourseAndStudent(any(), any());
    }
}
