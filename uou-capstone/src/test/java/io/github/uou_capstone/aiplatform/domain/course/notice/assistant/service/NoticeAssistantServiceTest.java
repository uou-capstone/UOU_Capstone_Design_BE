package io.github.uou_capstone.aiplatform.domain.course.notice.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.assistant.dto.NoticeAssistantRequest;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
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
class NoticeAssistantServiceTest {

    @Mock private CourseAccessService courseAccessService;
    @Mock private NoticeRepository noticeRepository;
    @Mock private FastApiBridgeClient fastApiBridgeClient;

    private NoticeAssistantService service;

    @BeforeEach
    void setUp() {
        service = new NoticeAssistantService(
                courseAccessService, noticeRepository, fastApiBridgeClient, new ObjectMapper());
    }

    @Test
    @DisplayName("notice assistant stream forwards course context and recent notices")
    void streamAssistantBuildsFastApiBody() {
        Course course = Course.builder()
                .title("Math")
                .description("desc")
                .invitationCode("ABC123")
                .build();
        ReflectionTestUtils.setField(course, "id", 1L);
        Notice recent = Notice.builder()
                .course(course)
                .title("Midterm")
                .contentMarkdown("body")
                .category(NoticeCategory.EXAM)
                .priority(NoticePriority.IMPORTANT)
                .build();

        NoticeAssistantRequest req = new NoticeAssistantRequest();
        req.setTopic("Midterm schedule");
        req.setCategory(NoticeCategory.EXAM);
        req.setPriority(NoticePriority.IMPORTANT);
        req.setPreviousDraft("Draft");

        when(courseAccessService.loadCourseAsTeacher(1L)).thenReturn(course);
        when(noticeRepository.findTop5ByCourseOrderByCreatedAtDesc(course)).thenReturn(List.of(recent));
        when(fastApiBridgeClient.noticeAssistantStream(any())).thenReturn(Flux.empty());

        service.streamAssistant(1L, req);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).noticeAssistantStream(captor.capture());
        Map<String, Object> body = captor.getValue();

        assertThat(body).containsEntry("courseId", 1L);
        assertThat(body).containsEntry("courseName", "Math");
        assertThat(body).containsEntry("topic", "Midterm schedule");
        assertThat(body).containsEntry("category", "EXAM");
        assertThat(body).containsEntry("priority", "IMPORTANT");
        assertThat(body).containsEntry("previousDraft", "Draft");
        assertThat((List<?>) body.get("recentNotices")).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> notice = (Map<String, Object>) ((List<?>) body.get("recentNotices")).get(0);
        assertThat(notice).containsEntry("title", "Midterm");
        assertThat(notice).containsEntry("category", "EXAM");
        assertThat(notice).containsEntry("priority", "IMPORTANT");
    }

    @Test
    @DisplayName("notice assistant maps upstream failures to sanitized SSE error")
    void streamAssistantMapsUpstreamError() {
        Course course = Course.builder()
                .title("Math")
                .description("desc")
                .invitationCode("ABC123")
                .build();
        ReflectionTestUtils.setField(course, "id", 1L);
        NoticeAssistantRequest req = new NoticeAssistantRequest();
        req.setTopic("Midterm schedule");

        when(courseAccessService.loadCourseAsTeacher(1L)).thenReturn(course);
        when(noticeRepository.findTop5ByCourseOrderByCreatedAtDesc(course)).thenReturn(List.of());
        when(fastApiBridgeClient.noticeAssistantStream(any()))
                .thenReturn(Flux.error(new IllegalStateException("internal stack detail")));

        var events = service.streamAssistant(1L, req).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).event()).isEqualTo("error");
        assertThat(events.get(0).data()).containsEntry("type", "error");
        assertThat(events.get(0).data()).containsEntry("code", "AI_SERVER_ERROR");
        assertThat(events.get(0).data()).containsEntry("message", "AI 서비스 호출 중 오류가 발생했습니다.");
    }
}
