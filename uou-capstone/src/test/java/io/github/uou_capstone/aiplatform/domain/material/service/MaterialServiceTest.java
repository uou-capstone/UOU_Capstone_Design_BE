package io.github.uou_capstone.aiplatform.domain.material.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningChatPersistenceService;
import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.StudentRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.TeacherRepository;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialServiceTest {

    private static final Long LECTURE_ID = 100L;
    private static final Long MATERIAL_ID = 200L;
    private static final Long TEACHER_ID = 10L;
    private static final Long USER_ID = 20L;

    private MaterialRepository materialRepository;
    private ExamSessionRepository examSessionRepository;
    private LectureRepository lectureRepository;
    private CurrentUserResolver currentUserResolver;
    private TeacherRepository teacherRepository;
    private StudentRepository studentRepository;
    private EnrollmentRepository enrollmentRepository;
    private FastApiSessionClient fastApiSessionClient;
    private LearningChatPersistenceService learningChatPersistenceService;
    private TransactionOperations transactionOperations;
    private MaterialService materialService;
    private Teacher teacher;
    private User user;
    private Lecture lecture;

    @BeforeEach
    void setUp() {
        materialRepository = mock(MaterialRepository.class);
        examSessionRepository = mock(ExamSessionRepository.class);
        lectureRepository = mock(LectureRepository.class);
        currentUserResolver = mock(CurrentUserResolver.class);
        teacherRepository = mock(TeacherRepository.class);
        studentRepository = mock(StudentRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        fastApiSessionClient = mock(FastApiSessionClient.class);
        learningChatPersistenceService = mock(LearningChatPersistenceService.class);
        transactionOperations = mock(TransactionOperations.class);

        materialService = new MaterialService(
                materialRepository,
                examSessionRepository,
                lectureRepository,
                mock(UserRepository.class),
                currentUserResolver,
                teacherRepository,
                uploadWebClient(),
                studentRepository,
                enrollmentRepository,
                mock(CourseRepository.class),
                fastApiSessionClient,
                learningChatPersistenceService,
                transactionOperations
        );

        teacher = teacher(TEACHER_ID);
        user = user(USER_ID);
        Course course = Course.builder()
                .teacher(teacher)
                .title("course")
                .description("desc")
                .invitationCode("code")
                .build();
        lecture = Lecture.builder()
                .course(course)
                .title("lecture")
                .weekNumber(1)
                .description("desc")
                .build();
        ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);

        when(transactionOperations.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void uploadFile_replacesPdfAndCleansLearningSessions() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes());
        when(lectureRepository.findById(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(user);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(learningChatPersistenceService.getActiveSessionIdsByLecture(LECTURE_ID)).thenReturn(List.of(300L, 301L));
        when(fastApiSessionClient.deleteSession(300L)).thenReturn(Mono.empty());
        when(fastApiSessionClient.deleteSession(301L)).thenReturn(Mono.empty());
        when(learningChatPersistenceService.endActiveSessionsByLecture(LECTURE_ID)).thenReturn(2);

        Material result = materialService.uploadFile(LECTURE_ID, file);

        assertThat(result.getFilePath()).isEqualTo("uploads/new.pdf");
        InOrder deleteOrder = inOrder(examSessionRepository, materialRepository);
        deleteOrder.verify(examSessionRepository).clearMaterialReferencesByLectureAndType(LECTURE_ID, "PDF");
        deleteOrder.verify(materialRepository).deleteByLecture_IdAndMaterialType(LECTURE_ID, "PDF");
        verify(materialRepository).save(any(Material.class));
        verify(fastApiSessionClient).deleteSession(300L);
        verify(fastApiSessionClient).deleteSession(301L);
        verify(learningChatPersistenceService).endActiveSessionsByLecture(LECTURE_ID);
    }

    @Test
    void deleteMaterial_deletesRowAndCleansLearningSessions() {
        Material material = material();
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(currentUserResolver.getUser()).thenReturn(user);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(learningChatPersistenceService.getActiveSessionIdsByLecture(LECTURE_ID)).thenReturn(List.of(300L));
        when(fastApiSessionClient.deleteSession(300L)).thenReturn(Mono.empty());

        materialService.deleteMaterial(MATERIAL_ID);

        InOrder deleteOrder = inOrder(examSessionRepository, materialRepository);
        deleteOrder.verify(examSessionRepository).clearMaterialReference(MATERIAL_ID);
        deleteOrder.verify(materialRepository).delete(material);
        deleteOrder.verify(materialRepository).flush();
        verify(fastApiSessionClient).deleteSession(300L);
        verify(learningChatPersistenceService).endActiveSessionsByLecture(LECTURE_ID);
    }

    @Test
    void deleteMaterial_keepsMaterialChangeWhenInvalidateFails() {
        Material material = material();
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(currentUserResolver.getUser()).thenReturn(user);
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(learningChatPersistenceService.getActiveSessionIdsByLecture(LECTURE_ID)).thenReturn(List.of(300L));
        when(fastApiSessionClient.deleteSession(300L))
                .thenReturn(Mono.error(new IllegalStateException("ai down")));

        materialService.deleteMaterial(MATERIAL_ID);

        InOrder deleteOrder = inOrder(examSessionRepository, materialRepository);
        deleteOrder.verify(examSessionRepository).clearMaterialReference(MATERIAL_ID);
        deleteOrder.verify(materialRepository).delete(material);
        deleteOrder.verify(materialRepository).flush();
        verify(learningChatPersistenceService).endActiveSessionsByLecture(LECTURE_ID);
    }

    @Test
    void deleteMaterial_doesNotCleanupWhenForbidden() {
        Teacher otherTeacher = teacher(TEACHER_ID + 1);
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material()));
        when(currentUserResolver.getUser()).thenReturn(user);
        when(currentUserResolver.getTeacher()).thenReturn(otherTeacher);

        assertThatThrownBy(() -> materialService.deleteMaterial(MATERIAL_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verify(examSessionRepository, never()).clearMaterialReference(any());
        verify(fastApiSessionClient, never()).deleteSession(any());
        verify(learningChatPersistenceService, never()).endActiveSessionsByLecture(LECTURE_ID);
    }

    @Test
    void streamFile_loadsMaterialWithLectureAndCourseBeforeReturningStreamingBody() {
        Material material = material();
        when(materialRepository.findByIdWithLectureAndCourse(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(currentUserResolver.getUser()).thenReturn(user);
        when(teacherRepository.findByUser_Id(USER_ID)).thenReturn(Optional.of(teacher));
        when(studentRepository.findByUser_Id(USER_ID)).thenReturn(Optional.empty());

        StreamingResponseBody body = materialService.streamFile(MATERIAL_ID);

        assertThat(body).isNotNull();
        verify(materialRepository).findByIdWithLectureAndCourse(MATERIAL_ID);
        verify(materialRepository, never()).findById(MATERIAL_ID);
    }

    private Material material() {
        return Material.builder()
                .lecture(lecture)
                .displayName("old.pdf")
                .materialType("PDF")
                .filePath("uploads/old.pdf")
                .uploadedBy(USER_ID)
                .build();
    }

    private static WebClient uploadWebClient() {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body("{\"filename\":\"new.pdf\",\"path\":\"uploads/new.pdf\"}")
                                .build()))
                .build();
    }

    private static Teacher teacher(Long id) {
        Teacher teacher = Teacher.builder().schoolName("school").department("dept").build();
        ReflectionTestUtils.setField(teacher, "id", id);
        return teacher;
    }

    private static User user(Long id) {
        User user = User.builder().email("u@example.com").password("p").fullName("user").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
