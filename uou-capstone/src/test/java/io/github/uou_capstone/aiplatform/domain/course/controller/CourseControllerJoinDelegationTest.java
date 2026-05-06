package io.github.uou_capstone.aiplatform.domain.course.controller;

import io.github.uou_capstone.aiplatform.domain.course.dto.CourseJoinRequestCreateDto;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseJoinRequestService;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CourseControllerJoinDelegationTest {

    @Mock private CourseService courseService;
    @Mock private CourseJoinRequestService joinRequestService;

    @InjectMocks
    private CourseController controller;

    @Test
    void deprecatedJoinEndpoint_delegatesToJoinRequestService() {
        ResponseEntity<String> response = controller.joinCourse("invite-code");

        ArgumentCaptor<CourseJoinRequestCreateDto> captor =
                ArgumentCaptor.forClass(CourseJoinRequestCreateDto.class);
        verify(joinRequestService).createJoinRequest(captor.capture());
        assertThat(captor.getValue().getInvitationCode()).isEqualTo("invite-code");
        verifyNoInteractions(courseService);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains("가입 요청");
    }
}
