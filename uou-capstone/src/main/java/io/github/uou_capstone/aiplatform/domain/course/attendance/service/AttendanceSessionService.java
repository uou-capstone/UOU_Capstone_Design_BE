package io.github.uou_capstone.aiplatform.domain.course.attendance.service;

import io.github.uou_capstone.aiplatform.common.dto.PageResponse;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.web.PageableSupport;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionCreateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionResponseDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.dto.AttendanceSessionUpdateRequestDto;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceRecord;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceSession;
import io.github.uou_capstone.aiplatform.domain.course.attendance.entity.AttendanceStatus;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceRecordRepository;
import io.github.uou_capstone.aiplatform.domain.course.attendance.repository.AttendanceSessionRepository;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.entity.Enrollment;
import io.github.uou_capstone.aiplatform.domain.course.entity.EnrollmentStatus;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.service.CourseAccessService;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttendanceSessionService {

    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "updatedAt", "sessionDate");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "sessionDate");

    private final AttendanceSessionRepository sessionRepository;
    private final AttendanceRecordRepository recordRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LectureRepository lectureRepository;
    private final CourseAccessService courseAccessService;
    private final CurrentUserResolver currentUserResolver;

    /**
     * 회차 생성. 같은 트랜잭션 안에서 ACTIVE 수강생 전체에 대해 ABSENT record 일괄 자동 생성.
     */
    @Transactional
    public AttendanceSessionResponseDto createSession(Long courseId,
                                                      AttendanceSessionCreateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Teacher teacher = currentUserResolver.getTeacher();

        Lecture lecture = null;
        if (dto.getLectureId() != null) {
            lecture = lectureRepository.findById(dto.getLectureId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
            if (!lecture.getCourse().getId().equals(course.getId())) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "lecture 가 해당 강의실 소속이 아닙니다.");
            }
        }

        AttendanceSession session = sessionRepository.save(
                AttendanceSession.builder()
                        .course(course)
                        .lecture(lecture)
                        .title(dto.getTitle())
                        .sessionDate(dto.getSessionDate())
                        .startTime(dto.getStartTime())
                        .endTime(dto.getEndTime())
                        .createdBy(teacher)
                        .build()
        );

        // ACTIVE 수강생 전체에 ABSENT record 자동 생성
        List<Enrollment> active = enrollmentRepository
                .findByCourseAndStatusWithStudentUser(course, EnrollmentStatus.ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        List<AttendanceRecord> records = new ArrayList<>(active.size());
        for (Enrollment e : active) {
            records.add(AttendanceRecord.builder()
                    .session(session)
                    .student(e.getStudent())
                    .status(AttendanceStatus.ABSENT)
                    .markedAt(now)
                    .markedBy(teacher)
                    .build());
        }
        recordRepository.saveAll(records);

        return new AttendanceSessionResponseDto(session);
    }

    @Transactional(readOnly = true)
    public PageResponse<AttendanceSessionResponseDto> listSessions(Long courseId, Pageable rawPageable) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        Pageable pageable = PageableSupport.validate(rawPageable, SORT_WHITELIST, DEFAULT_SORT);
        Page<AttendanceSession> page = sessionRepository.findByCourse(course, pageable);
        return PageResponse.of(page.map(AttendanceSessionResponseDto::new));
    }

    @Transactional(readOnly = true)
    public AttendanceSessionResponseDto getSession(Long courseId, Long sessionId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        AttendanceSession session = sessionRepository.findByIdAndCourse(sessionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return new AttendanceSessionResponseDto(session);
    }

    @Transactional
    public AttendanceSessionResponseDto updateSession(Long courseId,
                                                      Long sessionId,
                                                      AttendanceSessionUpdateRequestDto dto) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        AttendanceSession session = sessionRepository.findByIdAndCourse(sessionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Lecture newLecture = session.getLecture();
        if (dto.getLectureId() != null) {
            Lecture l = lectureRepository.findById(dto.getLectureId())
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));
            if (!l.getCourse().getId().equals(course.getId())) {
                throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                        "lecture 가 해당 강의실 소속이 아닙니다.");
            }
            newLecture = l;
        }

        session.update(dto.getTitle(),
                dto.getSessionDate(),
                dto.getStartTime(),
                dto.getEndTime(),
                newLecture);

        return new AttendanceSessionResponseDto(session);
    }

    @Transactional
    public void deleteSession(Long courseId, Long sessionId) {
        Course course = courseAccessService.loadCourseAsTeacher(courseId);
        AttendanceSession session = sessionRepository.findByIdAndCourse(sessionId, course)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        sessionRepository.delete(session); // cascade로 records 정리
    }
}
