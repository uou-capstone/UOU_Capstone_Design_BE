package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.report.studentanalysis.service.StudentReportAnalysisInvalidationService;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningSessionEvidence;
import io.github.uou_capstone.aiplatform.domain.learning.repository.LearningSessionEvidenceRepository;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningSessionEvidenceServiceTest {

    @Mock
    private LearningSessionEvidenceRepository evidenceRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private StudentReportAnalysisInvalidationService analysisInvalidationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private LearningSessionEvidenceService service;

    @Test
    void saveFromStreamLine_savesDoneLearningEvidence() {
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        Long sessionId = 5L;
        Long lectureId = 100L;
        Student student = Student.builder().grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 50L);
        User user = User.builder().email("s@example.com").password("p").fullName("s").build();
        ReflectionTestUtils.setField(user, "student", student);

        Course courseRef = Course.builder().teacher(null).title("c").description("d").invitationCode("i").build();
        Lecture lectureRef = Lecture.builder().course(courseRef).title("l").weekNumber(1).description("d").build();
        LearningChatSession sessionRef = LearningChatSession.builder().lecture(lectureRef).user(user).build();
        Material materialRef = Material.builder().lecture(lectureRef).displayName("m").materialType("PDF")
                .filePath("p").url(null).uploadedBy(1L).build();

        when(entityManager.getReference(Course.class, 10L)).thenReturn(courseRef);
        when(entityManager.getReference(Lecture.class, lectureId)).thenReturn(lectureRef);
        when(entityManager.getReference(Material.class, 20L)).thenReturn(materialRef);
        when(entityManager.getReference(Student.class, 50L)).thenReturn(student);
        when(entityManager.getReference(LearningChatSession.class, sessionId)).thenReturn(sessionRef);

        String line = """
                {"type":"done","data":{"learningEvidence":{"type":"learning_evidence","evidenceId":"ev-1","courseId":10,"materialId":20,"pageNumber":3,"eventType":"QUIZ_GRADED","quizType":"OX","scoreRatio":0.75,"passed":true,"weakConcepts":["CBR"],"wrongItems":[{"id":1}],"occurredAt":"2026-01-02T03:04:05"}}}
                """.trim();

        service.saveFromStreamLine(line, sessionId, lectureId, user);

        ArgumentCaptor<LearningSessionEvidence> captor = ArgumentCaptor.forClass(LearningSessionEvidence.class);
        verify(evidenceRepository).saveAndFlush(captor.capture());
        LearningSessionEvidence saved = captor.getValue();
        assertThat(saved.getEvidenceId()).isEqualTo("ev-1");
        assertThat(saved.getPageNumber()).isEqualTo(3);
        assertThat(saved.getEventType()).isEqualTo("QUIZ_GRADED");
        assertThat(saved.getQuizType()).isEqualTo("OX");
        assertThat(saved.getScoreRatio()).isEqualTo(0.75);
        assertThat(saved.getPassed()).isTrue();
        assertThat(saved.getWeakConcepts()).containsExactly("CBR");
        assertThat(saved.getWrongItems()).hasSize(1);
        assertThat(saved.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        verify(analysisInvalidationService).invalidateStudent(10L, 50L, "learning_evidence_created");
    }

    @Test
    void saveFromStreamLine_savesNestedFastApiLearningEvidence() {
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        Long sessionId = 5L;
        Long lectureId = 100L;
        Student student = Student.builder().grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 50L);
        User user = User.builder().email("s@example.com").password("p").fullName("s").build();
        ReflectionTestUtils.setField(user, "student", student);

        Course courseRef = Course.builder().teacher(null).title("c").description("d").invitationCode("i").build();
        Lecture lectureRef = Lecture.builder().course(courseRef).title("l").weekNumber(1).description("d").build();
        LearningChatSession sessionRef = LearningChatSession.builder().lecture(lectureRef).user(user).build();

        when(entityManager.getReference(Course.class, 10L)).thenReturn(courseRef);
        when(entityManager.getReference(Lecture.class, lectureId)).thenReturn(lectureRef);
        when(entityManager.getReference(Student.class, 50L)).thenReturn(student);
        when(entityManager.getReference(LearningChatSession.class, sessionId)).thenReturn(sessionRef);

        String line = """
                {"type":"done","data":{"learningEvidence":{"type":"learning_evidence","evidenceId":"ev-2","courseId":10,"pageNumber":4,"eventType":"QUIZ_GRADED","quiz":{"quizId":"q-1","quizType":"OX_Problem"},"grading":{"scoreRatio":0.4,"passed":false,"wrongItems":[{"id":2}]},"diagnosis":{"weakConcepts":["분수 통분"]},"createdAt":"2026-01-03T04:05:06Z"}}}
                """.trim();

        service.saveFromStreamLine(line, sessionId, lectureId, user);

        ArgumentCaptor<LearningSessionEvidence> captor = ArgumentCaptor.forClass(LearningSessionEvidence.class);
        verify(evidenceRepository).saveAndFlush(captor.capture());
        LearningSessionEvidence saved = captor.getValue();
        assertThat(saved.getEvidenceId()).isEqualTo("ev-2");
        assertThat(saved.getQuizType()).isEqualTo("OX_Problem");
        assertThat(saved.getScoreRatio()).isEqualTo(0.4);
        assertThat(saved.getPassed()).isFalse();
        assertThat(saved.getWeakConcepts()).containsExactly("분수 통분");
        assertThat(saved.getWrongItems()).hasSize(1);
        assertThat(saved.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 1, 3, 4, 5, 6));
        verify(analysisInvalidationService).invalidateStudent(10L, 50L, "learning_evidence_created");
    }

    @Test
    void saveFromStreamLine_ignoresDoneWithoutLearningEvidenceType() {
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        Student student = Student.builder().grade(1).classNumber("1-1").build();
        User user = User.builder().email("s@example.com").password("p").fullName("s").build();
        ReflectionTestUtils.setField(user, "student", student);

        String line = """
                {"type":"done","data":{"learningEvidence":{"evidenceId":"ev-1","scoreRatio":0.75}}}
                """.trim();

        service.saveFromStreamLine(line, 5L, 100L, user);

        verify(evidenceRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(entityManager, never()).getReference(eq(Course.class), org.mockito.ArgumentMatchers.any());
        verify(analysisInvalidationService, never()).invalidateStudent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void saveFromStreamLine_doesNotInvalidateWhenDuplicateSaveFails() {
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        Long sessionId = 5L;
        Long lectureId = 100L;
        Student student = Student.builder().grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(student, "id", 50L);
        User user = User.builder().email("s@example.com").password("p").fullName("s").build();
        ReflectionTestUtils.setField(user, "student", student);

        Course courseRef = Course.builder().teacher(null).title("c").description("d").invitationCode("i").build();
        Lecture lectureRef = Lecture.builder().course(courseRef).title("l").weekNumber(1).description("d").build();
        LearningChatSession sessionRef = LearningChatSession.builder().lecture(lectureRef).user(user).build();

        when(entityManager.getReference(Course.class, 10L)).thenReturn(courseRef);
        when(entityManager.getReference(Lecture.class, lectureId)).thenReturn(lectureRef);
        when(entityManager.getReference(Student.class, 50L)).thenReturn(student);
        when(entityManager.getReference(LearningChatSession.class, sessionId)).thenReturn(sessionRef);
        when(evidenceRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        String line = """
                {"type":"done","data":{"learningEvidence":{"type":"learning_evidence","evidenceId":"ev-dup","courseId":10,"eventType":"QUIZ_GRADED","scoreRatio":0.75}}}
                """.trim();

        service.saveFromStreamLine(line, sessionId, lectureId, user);

        verify(analysisInvalidationService, never()).invalidateStudent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
