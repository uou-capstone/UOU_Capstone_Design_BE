package io.github.uou_capstone.aiplatform.domain.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.learning.dto.SessionEventRequest;
import io.github.uou_capstone.aiplatform.domain.learning.entity.LearningChatSession;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.integration.fastapi.FastApiSessionClient;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningSessionAuthorizationTest {

    @Mock
    private FastApiSessionClient fastApiSessionClient;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MaterialRepository materialRepository;

    @Mock
    private LectureRepository lectureRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private CurrentUserResolver currentUserResolver;

    @Mock
    private LearningChatPersistenceService chatPersistenceService;

    @InjectMocks
    private LearningSessionService learningSessionService;

    private static final Long LECTURE_ID = 100L;
    private static final Long COURSE_OWNER_TEACHER_ID = 10L;
    private static final Long OTHER_TEACHER_ID = 11L;

    @BeforeEach
    void setUpObjectMapper() {
        ReflectionTestUtils.setField(learningSessionService, "objectMapper", new ObjectMapper());
    }

    @Test
    void teacherWhoOwnsCourse_passes() {
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);

        User currentUser = userWith(courseOwner, null);
        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);

        learningSessionService.validateLectureAccess(LECTURE_ID);
    }

    @Test
    void teacherFromAnotherCourse_isForbidden() {
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Teacher otherTeacher = teacher(OTHER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);

        User currentUser = userWith(otherTeacher, null);
        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);

        assertThatThrownBy(() -> learningSessionService.validateLectureAccess(LECTURE_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    @Test
    void enrolledStudent_passes() {
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);

        Student student = student(50L);
        User currentUser = userWith(null, student);
        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(true);

        learningSessionService.validateLectureAccess(LECTURE_ID);
    }

    @Test
    void notEnrolledStudent_isForbidden() {
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);

        Student student = student(51L);
        User currentUser = userWith(null, student);
        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);
        when(enrollmentRepository.existsByStudentAndCourse(student, course)).thenReturn(false);

        assertThatThrownBy(() -> learningSessionService.validateLectureAccess(LECTURE_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    @Test
    void unknownLecture_throwsLectureNotFound() {
        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> learningSessionService.validateLectureAccess(LECTURE_ID))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.LECTURE_NOT_FOUND));
    }

    @Test
    void streamUserMessage_savesUserQuestionAndAssistantMainDelta() {
        Long sessionId = 5L;
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);
        User currentUser = userWith(courseOwner, null);
        ReflectionTestUtils.setField(currentUser, "id", 77L);

        SessionEventRequest request = new SessionEventRequest();
        request.setType("USER_MESSAGE");
        request.setExtra("question", "hello");

        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);
        when(chatPersistenceService.getOwnedSession(sessionId, 77L, LECTURE_ID))
                .thenReturn(LearningChatSession.builder().lecture(lecture).user(currentUser).build());
        when(fastApiSessionClient.streamEvent(eq(sessionId), anyMap())).thenReturn(Flux.just(
                "{\"type\":\"agent_delta\",\"channel\":\"thought\",\"delta\":\"hidden\"}",
                "{\"type\":\"agent_delta\",\"channel\":\"main\",\"delta\":\"answer\"}",
                "{\"type\":\"done\",\"final\":true}"
        ));

        Flux<ServerSentEvent<String>> result = learningSessionService.streamSessionEvent(
                LECTURE_ID, sessionId, request, 2, null, null);

        List<ServerSentEvent<String>> events = result.collectList().block();
        assertThat(events).hasSize(4);

        verify(chatPersistenceService).saveUserMessage(sessionId, 77L, LECTURE_ID, "hello", 2);
        verify(chatPersistenceService).saveAssistantMessage(sessionId, 77L, LECTURE_ID, "answer", 2);
    }

    @Test
    void createOrGetSession_withoutSessionId_reusesActiveChatSession() {
        Long chatSessionId = 12L;
        Teacher courseOwner = teacher(COURSE_OWNER_TEACHER_ID);
        Course course = courseOwnedBy(courseOwner);
        Lecture lecture = lectureOf(course);
        User currentUser = userWith(courseOwner, null);
        LearningChatSession chatSession = LearningChatSession.builder()
                .lecture(lecture)
                .user(currentUser)
                .build();
        ReflectionTestUtils.setField(chatSession, "id", chatSessionId);

        when(lectureRepository.findByIdWithCourse(LECTURE_ID)).thenReturn(Optional.of(lecture));
        when(currentUserResolver.getUser()).thenReturn(currentUser);
        when(chatPersistenceService.getOrCreateActiveSession(LECTURE_ID, currentUser)).thenReturn(chatSession);
        when(materialRepository.findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(LECTURE_ID, "PDF"))
                .thenReturn(Optional.empty());
        when(fastApiSessionClient.getOrCreateByLecture(LECTURE_ID, null, chatSessionId))
                .thenReturn(Mono.just(Map.of("session_id", chatSessionId)));

        Map<String, Object> response = learningSessionService.getOrCreateSession(LECTURE_ID, null, null).block();

        assertThat(response).containsEntry("chatSessionId", chatSessionId);
        verify(fastApiSessionClient).getOrCreateByLecture(LECTURE_ID, null, chatSessionId);
    }

    private static Teacher teacher(Long id) {
        Teacher t = Teacher.builder().schoolName("s").department("d").build();
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static Student student(Long id) {
        Student s = Student.builder().grade(1).classNumber("1-1").build();
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static Course courseOwnedBy(Teacher teacher) {
        return Course.builder()
                .teacher(teacher)
                .title("course")
                .description("desc")
                .invitationCode("code")
                .build();
    }

    private static Lecture lectureOf(Course course) {
        Lecture lecture = Lecture.builder()
                .course(course)
                .title("lec")
                .weekNumber(1)
                .description("d")
                .build();
        ReflectionTestUtils.setField(lecture, "id", LECTURE_ID);
        return lecture;
    }

    private static User userWith(Teacher teacher, Student student) {
        User user = User.builder().email("u@example.com").password("p").fullName("u").build();
        ReflectionTestUtils.setField(user, "teacher", teacher);
        ReflectionTestUtils.setField(user, "student", student);
        return user;
    }
}
