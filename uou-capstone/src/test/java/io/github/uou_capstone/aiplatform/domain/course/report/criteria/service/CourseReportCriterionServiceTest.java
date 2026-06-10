package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaSummaryResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionCreateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionUpdateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseReportCriterionServiceTest {

    private static final long COURSE_ID = 1L;

    @Mock private CourseAccessService courseAccessService;
    @Mock private CourseReportCriterionRepository repository;
    @Mock private StudentReportAnalysisInvalidationService analysisInvalidationService;

    private CourseReportCriteriaCatalog catalog;
    private CourseReportCriteriaQueryService queryService;
    private CourseReportCriterionService service;
    private Course course;

    @BeforeEach
    void setUp() {
        catalog = new CourseReportCriteriaCatalog();
        queryService = new CourseReportCriteriaQueryService(catalog, repository);
        service = new CourseReportCriterionService(
                courseAccessService, repository, catalog, queryService, analysisInvalidationService);
        course = Course.builder().title("course").description("d").invitationCode("code").build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);
    }

    @Test
    void listAllReturnsBuiltInsThenCustomCriteria() {
        CourseReportCriterion custom = CourseReportCriterion.builder()
                .course(course)
                .label("발표 참여도")
                .description("발표 시도")
                .weight(20)
                .build();
        ReflectionTestUtils.setField(custom, "id", 11L);

        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(repository.findByCourseOrderByIdAsc(course)).thenReturn(List.of(custom));

        List<ReportCriterionResponse> res = service.listAll(COURSE_ID);

        assertThat(res).hasSize(11);
        assertThat(res.get(0).getId()).isEqualTo("builtin:CONCEPT_UNDERSTANDING");
        assertThat(res.get(0).isBuiltIn()).isTrue();
        assertThat(res.get(0).isEditable()).isFalse();
        assertThat(res.get(0).isDeletable()).isFalse();
        assertThat(res.get(10).getId()).isEqualTo("custom:11");
        assertThat(res.get(10).getCriterionId()).isEqualTo(11L);
        assertThat(res.get(10).isBuiltIn()).isFalse();
        assertThat(res.get(10).isEditable()).isTrue();
        assertThat(res.get(10).isDeletable()).isTrue();
    }

    @Test
    void builtInCriteriaExposeCanonicalKeysAndBuiltInFlag() {
        List<ReportCriterionResponse> res = catalog.builtInCriteria();

        assertThat(res).hasSize(10);
        assertThat(res).extracting(ReportCriterionResponse::getKey).containsExactly(
                "CONCEPT_UNDERSTANDING",
                "QUESTION_SPECIFICITY",
                "PROBLEM_SOLVING",
                "APPLICATION_TRANSFER",
                "QUIZ_ACCURACY",
                "LEARNING_PERSISTENCE",
                "WRONG_ANSWER_REFLECTION",
                "CLASS_PARTICIPATION",
                "LEARNING_CONFIDENCE",
                "GROWTH_MOMENTUM");
        assertThat(res).allSatisfy(criterion -> {
            assertThat(criterion.getId()).isEqualTo("builtin:" + criterion.getKey());
            assertThat(criterion.isBuiltIn()).isTrue();
            assertThat(criterion.isEditable()).isFalse();
            assertThat(criterion.isDeletable()).isFalse();
        });
    }

    @Test
    void summaryPreservesCustomCriteriaStatusAndAddsReportCriteriaTotal() {
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(repository.findByCourseOrderByIdAsc(course)).thenReturn(List.of());

        CriteriaSummaryResponse res = service.summary(COURSE_ID);

        assertThat(res.getBaseItemCount()).isEqualTo(10);
        assertThat(res.getAdditionalItemCount()).isZero();
        assertThat(res.getActiveCriteriaCount()).isZero();
        assertThat(res.getCriteriaStatus()).isEqualTo(CriteriaStatus.NONE.name());
        assertThat(res.getReportCriteriaCount()).isEqualTo(10);
        assertThat(res.getReportCriteriaStatus()).isEqualTo(CriteriaStatus.ACTIVE.name());
    }

    @Test
    void createInvalidatesCourseAnalyses() {
        CriterionCreateRequest req = new CriterionCreateRequest();
        req.setLabel("participation");
        req.setDescription("participation");
        req.setWeight(10);
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(repository.save(any(CourseReportCriterion.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(COURSE_ID, req);

        verify(analysisInvalidationService).invalidateCourse(COURSE_ID, "report_criterion_created");
    }

    @Test
    void updateInvalidatesCourseAnalyses() {
        CourseReportCriterion custom = CourseReportCriterion.builder()
                .course(course)
                .label("old")
                .description("old")
                .weight(10)
                .build();
        CriterionUpdateRequest req = new CriterionUpdateRequest();
        req.setLabel("new");
        req.setDescription("new");
        req.setWeight(20);
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(repository.findByIdAndCourse(11L, course)).thenReturn(Optional.of(custom));

        service.update(COURSE_ID, 11L, req);

        verify(analysisInvalidationService).invalidateCourse(COURSE_ID, "report_criterion_updated");
    }

    @Test
    void deleteInvalidatesCourseAnalyses() {
        CourseReportCriterion custom = CourseReportCriterion.builder()
                .course(course)
                .label("old")
                .description("old")
                .weight(10)
                .build();
        when(courseAccessService.loadCourseAsTeacher(COURSE_ID)).thenReturn(course);
        when(repository.findByIdAndCourse(11L, course)).thenReturn(Optional.of(custom));

        service.delete(COURSE_ID, 11L);

        verify(repository).delete(custom);
        verify(analysisInvalidationService).invalidateCourse(COURSE_ID, "report_criterion_deleted");
    }
}
