package io.github.uou_capstone.aiplatform.domain.notification.controller;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.domain.notification.dto.NotificationItemDto;
import io.github.uou_capstone.aiplatform.domain.notification.dto.TeacherNotificationPreferenceDto;
import io.github.uou_capstone.aiplatform.domain.notification.dto.UnreadCountResponse;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationStreamRegistry;
import io.github.uou_capstone.aiplatform.domain.notification.service.TeacherNotificationPreferenceService;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import io.github.uou_capstone.aiplatform.util.sse.SseEventNames;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamPolicy;
import io.github.uou_capstone.aiplatform.util.sse.SseStreamSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Tag(name = "알림 API", description = "사용자 알림 목록 조회 / 읽음 처리 / 실시간 SSE 스트림")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationStreamRegistry streamRegistry;
    private final CurrentUserResolver currentUserResolver;
    private final TeacherNotificationPreferenceService teacherPreferenceService;

    @Operation(summary = "내 알림 목록 조회",
               description = "현재 로그인 사용자의 알림을 최신순으로 페이지 단위 반환합니다. 정렬 허용 필드: createdAt.")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('STUDENT','TEACHER')")
    public ResponseEntity<PageResponse<NotificationItemDto>> getMyNotifications(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(notificationService.getMyNotifications(pageable));
    }

    @Operation(summary = "안 읽은 알림 개수",
               description = "현재 로그인 사용자의 readAt이 null인 알림 개수를 반환합니다.")
    @GetMapping("/unread-count")
    @PreAuthorize("hasAnyAuthority('STUDENT','TEACHER')")
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.getUnreadCount()));
    }

    @Operation(summary = "알림 읽음 처리",
               description = "지정한 알림을 읽음 처리합니다. 본인 알림이 아니면 404로 응답합니다.")
    @PostMapping("/{notificationId}/read")
    @PreAuthorize("hasAnyAuthority('STUDENT','TEACHER')")
    public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "모든 알림 읽음 처리",
               description = "현재 로그인 사용자의 안 읽은 알림 전체를 읽음 처리합니다.")
    @PostMapping("/read-all")
    @PreAuthorize("hasAnyAuthority('STUDENT','TEACHER')")
    public ResponseEntity<Void> markAllAsRead() {
        notificationService.markAllAsRead();
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "교사 알림 수신 설정 조회",
               description = "현재 로그인 교사의 알림 수신 설정을 반환합니다. row 가 없으면 기본값(false)으로 lazy-create 합니다.")
    @GetMapping("/teacher-preferences")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<TeacherNotificationPreferenceDto> getTeacherPreferences() {
        return ResponseEntity.ok(teacherPreferenceService.getOrCreate());
    }

    @Operation(summary = "교사 알림 수신 설정 변경",
               description = "현재 로그인 교사의 알림 수신 설정을 갱신합니다. includeSelfActionNotifications=true 시 본인 작업 확인 알림(TEACHER_ACTION_CONFIRMED) 도 수신합니다.")
    @PatchMapping("/teacher-preferences")
    @PreAuthorize("hasAuthority('TEACHER')")
    public ResponseEntity<TeacherNotificationPreferenceDto> updateTeacherPreferences(
            @RequestBody TeacherNotificationPreferenceDto dto) {
        return ResponseEntity.ok(teacherPreferenceService.update(dto));
    }

    @Operation(summary = "알림 실시간 SSE 스트림",
               description = "현재 로그인 사용자의 실시간 알림 이벤트를 SSE로 수신합니다. " +
                       "이벤트 표준: message / heartbeat / timeout / error / done. SSE 미연결 중 발생한 알림은 GET /api/notifications 로 복구 가능합니다.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyAuthority('STUDENT','TEACHER')")
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        Long userId = currentUserResolver.getUser().getId();

        Flux<ServerSentEvent<Map<String, Object>>> source = streamRegistry.register(userId);

        SseStreamPolicy policy = SseStreamPolicy.builder()
                .idleTimeout(java.time.Duration.ofMinutes(5))
                .appendDoneOnComplete(false)
                .build();

        return SseStreamSupport.wrapEvents(
                source,
                policy,
                error -> {
                    log.error("notification stream error userId={}", userId, error);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", "error");
                    payload.put("message", error.getMessage() != null ? error.getMessage() : "알 수 없는 오류");
                    return ServerSentEvent.<Map<String, Object>>builder()
                            .event(SseEventNames.ERROR)
                            .data(payload)
                            .build();
                }
        );
    }
}
