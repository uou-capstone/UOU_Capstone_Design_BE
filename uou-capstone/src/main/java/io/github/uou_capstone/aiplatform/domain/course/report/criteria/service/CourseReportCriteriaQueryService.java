package io.github.uou_capstone.aiplatform.domain.course.report.criteria.service;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.dto.ReportCriterionResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CourseReportCriteriaQueryService {

    private final CourseReportCriteriaCatalog catalog;
    private final CourseReportCriterionRepository repository;

    @Transactional(readOnly = true)
    public List<ReportCriterionResponse> listAll(Course course) {
        List<ReportCriterionResponse> customCriteria = repository.findByCourseOrderByIdAsc(course).stream()
                .map(ReportCriterionResponse::custom)
                .toList();
        return Stream.concat(catalog.builtInCriteria().stream(), customCriteria.stream())
                .toList();
    }
}
