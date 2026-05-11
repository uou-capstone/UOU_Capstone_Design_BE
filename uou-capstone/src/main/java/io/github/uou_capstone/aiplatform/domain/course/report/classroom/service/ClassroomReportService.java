package io.github.uou_capstone.aiplatform.domain.course.report.classroom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.dto.ClassroomReportResponse;
import io.github.uou_capstone.aiplatform.domain.course.report.classroom.repository.ClassroomReportRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.entity.CourseReportCriterion;
import io.github.uou_capstone.aiplatform.domain.course.report.criteria.repository.CourseReportCriterionRepository;
import io.github.uou_capstone.aiplatform.domain.course.report.dto.StudentReportListItem;
import io.github.uou_capstone.aiplatform.domain.course.report.service.CourseStudentReportService;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import io.github.uou_capstone.aiplatform.util.sse.SseEventNames;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 강의실 종합 리포트 — 학생 리스트 + Criteria 를 모아 FastAPI 에 분석 요청.
 *
 * <p>두 호출 경로 모두 결과를 {@code classroom_reports} 1행으로 UPSERT (1 course = 1 row).
 *
 * <p>현재 MVP: 학생 데이터로 {@link StudentReportListItem} 만 forward (List 1000개 한계). 100명 이상
 * 강의실은 페이지 분할이 필요하나 이번 PR 범위에서 제외 (Open Items).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassroomReportService {

    private static final int MAX_STUDENT_PAGE_SIZE = 1000;
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(240);

    private final CourseAccessService courseAccessService;
    private final CourseStudentReportService courseStudentReportService;
    private final CourseReportCriterionRepository criterionRepository;
    private final ClassroomReportRepository classroomReportRepository;
    private final ClassroomReportPersister persister;
    private final FastApiBridgeClient fastApiBridgeClient;
    private final ObjectMapper objectMapper;

    public Optional<ClassroomReportResponse> get(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        return classroomReportRepository.findByCourse(course)
                .map(e -> new ClassroomReportResponse(e, objectMapper));
    }

    public Map<String, Object> analyze(Long courseId) {
        Map<String, Object> body = preparePayload(courseId);

        log.info("Classroom report analyze 시작 (동기): courseId={}", courseId);
        String raw = fastApiBridgeClient.reportClassroomAnalyze(body);
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.AI_SERVER_ERROR,
                    "Classroom 리포트 응답 파싱에 실패했습니다.");
        }
        persister.upsert(courseId, data);
        return data;
    }

    public Flux<ServerSentEvent<Map<String, Object>>> analyzeStream(Long courseId) {
        Map<String, Object> body = preparePayload(courseId);

        log.info("Classroom report analyze 시작 (스트림): courseId={}", courseId);
        Flux<String> upstream = fastApiBridgeClient.reportClassroomAnalyzeStream(body);
        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(STREAM_IDLE_TIMEOUT)
                .heartbeatPassthrough(true)
                .appendDoneOnComplete(false)
                .build();
        return SseStreamSupport.wrapNdjsonByType(upstream, objectMapper, policy, this::mapError)
                .doOnNext(event -> persistIfDone(courseId, event.event(), event.data()));
    }

    @SuppressWarnings("unchecked")
    private void persistIfDone(Long courseId, String eventName, Map<String, Object> payload) {
        if (!SseEventNames.DONE.equals(eventName) || payload == null) return;
        Object inner = payload.get("data");
        if (inner instanceof Map<?, ?> innerMap) {
            try {
                persister.upsert(courseId, (Map<String, Object>) innerMap);
            } catch (Exception ex) {
                log.error("Classroom report 스트림 UPSERT 실패: courseId={}", courseId, ex);
            }
        }
    }

    private Map<String, Object> preparePayload(Long courseId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        PageResponse<StudentReportListItem> page = courseStudentReportService.getStudentReportList(
                courseId, null, null, PageRequest.of(0, MAX_STUDENT_PAGE_SIZE));
        List<CourseReportCriterion> criteria = criterionRepository.findByCourseOrderByIdAsc(course);

        if (page.getTotalElements() > MAX_STUDENT_PAGE_SIZE) {
            log.warn("Classroom report: 학생 수가 {}명을 초과 — 일부만 분석에 포함됨 (total={})",
                    MAX_STUDENT_PAGE_SIZE, page.getTotalElements());
        }

        List<Map<String, Object>> criteriaDtos = criteria.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("label", c.getLabel());
                    m.put("description", c.getDescription());
                    m.put("weight", c.getWeight());
                    return m;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", course.getId());
        body.put("courseName", course.getTitle());
        body.put("studentReports", page.getContent());
        body.put("criteria", criteriaDtos);
        return body;
    }

    private Map<String, Object> mapError(Throwable e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "error");
        m.put("code", "AI_SERVER_ERROR");
        if (e instanceof WebClientResponseException ex) {
            log.error("FastAPI report/classroom_analyze 오류: status={}", ex.getStatusCode(), e);
            m.put("message", "AI 서비스 호출에 실패했습니다.");
        } else {
            log.error("FastAPI report/classroom_analyze 알 수 없는 오류", e);
            m.put("message", "AI 서비스 호출 중 오류가 발생했습니다.");
        }
        return m;
    }
}
