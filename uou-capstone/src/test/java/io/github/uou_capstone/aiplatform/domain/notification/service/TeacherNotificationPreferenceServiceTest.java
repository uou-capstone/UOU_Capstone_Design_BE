package io.github.uou_capstone.aiplatform.domain.notification.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.notification.dto.TeacherNotificationPreferenceDto;
import io.github.uou_capstone.aiplatform.domain.notification.entity.TeacherNotificationPreference;
import io.github.uou_capstone.aiplatform.domain.notification.repository.TeacherNotificationPreferenceRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherNotificationPreferenceServiceTest {

    @Mock private TeacherNotificationPreferenceRepository preferenceRepository;
    @Mock private CurrentUserResolver currentUserResolver;

    @InjectMocks
    private TeacherNotificationPreferenceService service;

    private Teacher teacher;

    @BeforeEach
    void setUp() {
        User user = User.builder().email("t@x").password("p").fullName("teacher").build();
        ReflectionTestUtils.setField(user, "id", 200L);
        teacher = Teacher.builder().schoolName("s").department("d").user(user).build();
        ReflectionTestUtils.setField(teacher, "id", 10L);
    }

    @Test
    void getOrCreate_returnsDefaultFalse_whenRowMissing_andLazyCreates() {
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(preferenceRepository.findByTeacherId(10L)).thenReturn(Optional.empty());
        when(preferenceRepository.save(any(TeacherNotificationPreference.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TeacherNotificationPreferenceDto dto = service.getOrCreate();

        assertThat(dto.isIncludeSelfActionNotifications()).isFalse();

        ArgumentCaptor<TeacherNotificationPreference> captor =
                ArgumentCaptor.forClass(TeacherNotificationPreference.class);
        verify(preferenceRepository).save(captor.capture());
        assertThat(captor.getValue().getTeacher()).isEqualTo(teacher);
        assertThat(captor.getValue().isIncludeSelfActionNotifications()).isFalse();
    }

    @Test
    void getOrCreate_returnsExisting_withoutSaving() {
        TeacherNotificationPreference existing = TeacherNotificationPreference.builder()
                .teacher(teacher)
                .includeSelfActionNotifications(true)
                .build();
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(preferenceRepository.findByTeacherId(10L)).thenReturn(Optional.of(existing));

        TeacherNotificationPreferenceDto dto = service.getOrCreate();

        assertThat(dto.isIncludeSelfActionNotifications()).isTrue();
        verify(preferenceRepository, never()).save(any());
    }

    @Test
    void update_changesValueOnExistingRow() {
        TeacherNotificationPreference existing = TeacherNotificationPreference.builder()
                .teacher(teacher)
                .includeSelfActionNotifications(false)
                .build();
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(preferenceRepository.findByTeacherId(10L)).thenReturn(Optional.of(existing));

        TeacherNotificationPreferenceDto result =
                service.update(new TeacherNotificationPreferenceDto(true));

        assertThat(result.isIncludeSelfActionNotifications()).isTrue();
        assertThat(existing.isIncludeSelfActionNotifications()).isTrue();
    }

    @Test
    void update_lazyCreatesAndChangesValue() {
        when(currentUserResolver.getTeacher()).thenReturn(teacher);
        when(preferenceRepository.findByTeacherId(10L)).thenReturn(Optional.empty());
        when(preferenceRepository.save(any(TeacherNotificationPreference.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TeacherNotificationPreferenceDto result =
                service.update(new TeacherNotificationPreferenceDto(true));

        assertThat(result.isIncludeSelfActionNotifications()).isTrue();
    }

    @Test
    void getOrCreate_throwsForNonTeacher() {
        when(currentUserResolver.getTeacher())
                .thenThrow(new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));

        assertThatThrownBy(() -> service.getOrCreate())
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void isSelfActionNotificationsEnabled_returnsFalse_whenRowMissing() {
        when(preferenceRepository.findByTeacherId(99L)).thenReturn(Optional.empty());

        assertThat(service.isSelfActionNotificationsEnabled(99L)).isFalse();
        verify(preferenceRepository, never()).save(any()); // 단순 조회는 lazy-create 안 함
    }

    @Test
    void isSelfActionNotificationsEnabled_returnsStoredValue() {
        TeacherNotificationPreference existing = TeacherNotificationPreference.builder()
                .teacher(teacher)
                .includeSelfActionNotifications(true)
                .build();
        when(preferenceRepository.findByTeacherId(10L)).thenReturn(Optional.of(existing));

        assertThat(service.isSelfActionNotificationsEnabled(10L)).isTrue();
    }
}
