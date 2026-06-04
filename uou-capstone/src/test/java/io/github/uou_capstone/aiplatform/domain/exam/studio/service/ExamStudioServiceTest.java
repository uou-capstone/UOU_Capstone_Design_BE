package io.github.uou_capstone.aiplatform.domain.exam.studio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioChatRequest;
import io.github.uou_capstone.aiplatform.domain.exam.studio.dto.ExamStudioPdfContextRequest;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiBridgeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamStudioServiceTest {

    @Mock private CourseAccessService courseAccessService;
    @Mock private MaterialRepository materialRepository;
    @Mock private FastApiBridgeClient fastApiBridgeClient;

    @Test
    @DisplayName("pdf context sends resolved pdf path to FastAPI")
    void issuePdfContext_sendsResolvedPdfPathToFastApi() {
        ExamStudioService service = new ExamStudioService(
                courseAccessService,
                materialRepository,
                fastApiBridgeClient,
                new ObjectMapper());
        ExamStudioPdfContextRequest request = new ExamStudioPdfContextRequest();
        request.setMaterialId(11L);

        io.github.uou_capstone.aiplatform.domain.course.entity.Course course =
                mock(io.github.uou_capstone.aiplatform.domain.course.entity.Course.class);
        io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture lecture =
                mock(io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture.class);
        Material material = mock(Material.class);

        when(course.getId()).thenReturn(7L);
        when(lecture.getId()).thenReturn(3L);
        when(lecture.getCourse()).thenReturn(course);
        when(material.getId()).thenReturn(11L);
        when(material.getLecture()).thenReturn(lecture);
        when(material.getMaterialType()).thenReturn("PDF");
        when(material.getFilePath()).thenReturn("uploads\\lecture.pdf");
        when(material.getDisplayName()).thenReturn("lecture.pdf");
        when(courseAccessService.loadCourseAsTeacher(7L)).thenReturn(course);
        when(materialRepository.findByIdWithLectureAndCourse(11L)).thenReturn(Optional.of(material));
        when(fastApiBridgeClient.examStudioPdfContext(any())).thenReturn("{\"contextId\":\"ctx-1\"}");

        Map<String, Object> response = service.issuePdfContext(7L, request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).examStudioPdfContext(captor.capture());
        assertThat(captor.getValue().get("materialId")).isEqualTo(11L);
        assertThat(captor.getValue().get("pdfPath")).isEqualTo("uploads/lecture.pdf");
        assertThat(captor.getValue().get("pdf_path")).isEqualTo("uploads/lecture.pdf");
        assertThat(response.get("contextId")).isEqualTo("ctx-1");
    }

    @Test
    @DisplayName("chat stream: currentDraft 가 null 이면 FastAPI 에 빈 객체로 전달한다")
    void streamChat_nullCurrentDraftSentAsEmptyObject() {
        ExamStudioService service = new ExamStudioService(
                courseAccessService,
                materialRepository,
                fastApiBridgeClient,
                new ObjectMapper());
        ExamStudioChatRequest request = new ExamStudioChatRequest();
        request.setContextId("ctx-1");
        request.setMessage("객관식 5문제 추가해줘");
        request.setCurrentDraft(null);
        when(fastApiBridgeClient.examStudioChatStream(any())).thenReturn(Flux.empty());

        service.streamChat(7L, request);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(fastApiBridgeClient).examStudioChatStream(captor.capture());
        assertThat(captor.getValue().get("currentDraft")).isEqualTo(Map.of());
    }
}
