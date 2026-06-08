package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaStatus;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriteriaSummaryResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionCreateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.CriterionUpdateRequest;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseReportCriterionService {

    private final CourseAccessService courseAccessService;
    private final CourseReportCriterionRepository repository;
    private final CourseReportCriteriaCatalog catalog;
    private final CourseReportCriteriaQueryService criteriaQueryService;

    @Transactional(readOnly = true)
    public List<CriterionResponse> list(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        return repository.findByCourseOrderByIdAsc(course).stream()
                .map(CriterionResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportCriterionResponse> listAll(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        return criteriaQueryService.listAll(course);
    }

    @Transactional(readOnly = true)
    public CriteriaSummaryResponse summary(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        List<CourseReportCriterion> criteria = repository.findByCourseOrderByIdAsc(course);
        LocalDateTime reflectedAt = criteria.stream()
                .map(c -> c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt())
                .max(Comparator.nullsLast(Comparator.naturalOrder()))
                .orElse(null);

        return CriteriaSummaryResponse.builder()
                .baseItemCount(catalog.builtInCount())
                .additionalItemCount(criteria.size())
                .activeCriteriaCount(criteria.size())
                .reportCriteriaCount(catalog.builtInCount() + criteria.size())
                .criteriaStatus(criteria.isEmpty() ? CriteriaStatus.NONE.name() : CriteriaStatus.ACTIVE.name())
                .reportCriteriaStatus(CriteriaStatus.ACTIVE.name())
                .criteriaReflectedAt(reflectedAt)
                .build();
    }

    @Transactional
    public CriterionResponse create(Long courseId, CriterionCreateRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        CourseReportCriterion saved = repository.save(
                CourseReportCriterion.builder()
                        .course(course)
                        .label(req.getLabel())
                        .description(req.getDescription())
                        .weight(req.getWeight())
                        .build());
        return new CriterionResponse(saved);
    }

    @Transactional
    public CriterionResponse update(Long courseId, Long criterionId, CriterionUpdateRequest req) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        CourseReportCriterion c = repository.findByIdAndCourse(criterionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        c.update(req.getLabel(), req.getDescription(), req.getWeight());
        return new CriterionResponse(c);
    }

    @Transactional
    public void delete(Long courseId, Long criterionId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        CourseReportCriterion c = repository.findByIdAndCourse(criterionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        repository.delete(c);
    }
}
