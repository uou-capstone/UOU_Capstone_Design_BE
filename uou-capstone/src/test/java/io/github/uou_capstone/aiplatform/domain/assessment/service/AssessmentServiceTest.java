package io.github.uou_capstone.aiplatform.domain.assessment.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.assessment.dto.AssessmentCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.AssessmentRepository;
import io.github.uou_capstone.aiplatform.domain.assessment.repository.ChoiceOptionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamQuestionRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private ExamQuestionRepository examQuestionRepository;

    @Mock
    private ChoiceOptionRepository choiceOptionRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private AssessmentService assessmentService;

    @Test
    void createAssessment_throwsForbiddenWhenTeacherDoesNotOwnCourse() {
        Long courseId = 1L;

        Teacher courseOwner = Teacher.builder()
                .schoolName("owner-school")
                .department("owner-dept")
                .build();
        ReflectionTestUtils.setField(courseOwner, "id", 10L);

        Teacher currentTeacher = Teacher.builder()
                .schoolName("current-school")
                .department("current-dept")
                .build();
        ReflectionTestUtils.setField(currentTeacher, "id", 20L);

        Course course = Course.builder()
                .teacher(courseOwner)
                .title("course")
                .description("desc")
                .invitationCode("invite-code")
                .build();

        when(courseRepository.findById(courseId)).thenReturn(Optional.of(course));
        when(currentUserResolver.getTeacher()).thenReturn(currentTeacher);

        assertThatThrownBy(() -> assessmentService.createAssessment(courseId, mock(AssessmentCreateRequestDto.class)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));

        verifyNoInteractions(assessmentRepository, examQuestionRepository, choiceOptionRepository);
    }
}
