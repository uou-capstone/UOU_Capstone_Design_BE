package io.github.uou_capstone.aiplatform.domain.course.notice.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeListItemResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.dto.NoticeUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.repository.NoticeRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.notification.entity.NotificationType;
import io.github.uou_capstone.aiplatform.domain.notification.service.NotificationService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NoticeService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt", "pinned");
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("pinned"),
            Sort.Order.desc("createdAt")
    );

    private static final String RESOURCE_TYPE = "NOTICE";

    private final NoticeRepository noticeRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;
    private final NotificationService notificationService;

    @Transactional
    public NoticeResponseDto createNotice(Long courseId, NoticeCreateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Teacher author = currentUserResolver.getTeacher();

        Notice saved = noticeRepository.save(
                Notice.builder()
                        .course(course)
                        .author(author)
                        .title(dto.getTitle())
                        .contentMarkdown(dto.getContentMarkdown())
                        .category(dto.getCategory())
                        .priority(dto.getPriority())
                        .pinned(dto.getPinned())
                        .build()
        );

        // ACTIVE 수강생에게 알림 발송 — student.user 까지 JOIN FETCH 로 한 번에 로드
        List<Enrollment> activeEnrollments =
                enrollmentRepository.findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE);
        for (Enrollment e : activeEnrollments) {
            notificationService.notify(
                    e.getStudent().getUser(),
                    NotificationType.NOTICE_PUBLISHED,
                    "새 공지: " + course.getTitle(),
                    saved.getTitle(),
                    RESOURCE_TYPE,
                    saved.getId()
            );
        }

        return new NoticeResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<NoticeListItemResponseDto> listNotices(Long courseId, Pageable rawPageable) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<Notice> page = noticeRepository.findByCourse(course, pageable);
        return PageResponse.of(page.map(NoticeListItemResponseDto::new));
    }

    @Transactional(readOnly = true)
    public NoticeResponseDto getNotice(Long courseId, Long noticeId) {
        Course course = courseAccessService.loadCourseAsParticipant(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new NoticeResponseDto(notice);
    }

    @Transactional
    public NoticeResponseDto updateNotice(Long courseId, Long noticeId, NoticeUpdateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        // 작성자만 수정 가능 — author 는 Teacher 이므로 user.id 로 비교
        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthor(currentUser, notice.getAuthor().getUser().getId());

        notice.update(dto.getTitle(),
                dto.getContentMarkdown(),
                dto.getCategory(),
                dto.getPriority(),
                dto.getPinned());

        return new NoticeResponseDto(notice);
    }

    @Transactional
    public void deleteNotice(Long courseId, Long noticeId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Notice notice = noticeRepository.findByIdAndCourse(noticeId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        // 작성자 또는 강의실 교사 — loadCourseAsTeacher 가 이미 교사 보장하므로
        // 사실상 강의실 교사이면 누구의 공지든 삭제 가능 (작성자 제한 없음)
        // 단, 작성자 본인이거나 강의실 owner 면 OK 인 정책이라면 ensureAuthorOrCourseTeacher 호출
        User currentUser = currentUserResolver.getUser();
        courseAccessService.ensureAuthorOrCourseTeacher(
                currentUser, course, notice.getAuthor().getUser().getId());

        noticeRepository.delete(notice);
    }
}
