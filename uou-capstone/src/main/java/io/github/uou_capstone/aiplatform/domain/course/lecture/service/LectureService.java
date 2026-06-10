package io.github.uou_capstone.aiplatform.domain.course.lecture.service;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.repository.CourseRepository;
import io.github.uou_capstone.aiplatform.domain.course.repository.EnrollmentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.dto.*;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.*;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.GeneratedContentRepository;
import io.github.uou_capstone.aiplatform.domain.course.lecture.repository.LectureRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamProfileRepository;
import io.github.uou_capstone.aiplatform.domain.exam.repository.ExamSessionRepository;
import io.github.uou_capstone.aiplatform.domain.learning.service.LearningDataCleanupService;
import io.github.uou_capstone.aiplatform.domain.material.generation.GenerationSessionRepository;
import io.github.uou_capstone.aiplatform.domain.material.repository.MaterialRepository;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.service.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
 
@Service
@RequiredArgsConstructor
public class LectureService {

    private final CourseRepository courseRepository;
    private final LectureRepository lectureRepository;
    private final GeneratedContentRepository generatedContentRepository;
    private final CurrentUserResolver currentUserResolver;
    private final EnrollmentRepository enrollmentRepository;
    private final MaterialRepository materialRepository;
    private final GenerationSessionRepository generationSessionRepository;
    private final ExamSessionRepository examSessionRepository;
    private final ExamProfileRepository examProfileRepository;
    private final LearningDataCleanupService learningDataCleanupService;

    @Transactional
    public Lecture createLecture(Long courseId, LectureCreateRequestDto requestDto) {
        // 1. 강의를 추가할 과목을 DB에서 조회
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COURSE_NOT_FOUND));


        // 2. 권한 확인: 현재 로그인한 사용자가 이 과목의 선생님인지 확인
        Teacher currentTeacher = currentUserResolver.getTeacher();

        if (!course.getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        // 3. 새로운 Lecture Entity 생성
        Lecture newLecture = Lecture.builder()
                .course(course)
                .title(requestDto.getTitle())
                .weekNumber(requestDto.getWeekNumber())
                .description(requestDto.getDescription())
                .build();

        // 4. 생성된 Lecture를 DB에 저장하고 반환
        return lectureRepository.save(newLecture);
    }

    @Transactional(readOnly = true)
    public LectureDetailResponseDto getLectureDetail(Long lectureId) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // 2. 권한 확인 로직
        Course course = lecture.getCourse();
        validateLectureParticipant(course);

        // 3. 해당 강의에 속한 AI 생성 콘텐츠 목록 조회
        List<GeneratedContent> contents = generatedContentRepository.findByLectureId(lectureId);

        // 4. DTO로 변환하여 반환
        return new LectureDetailResponseDto(lecture, contents);
    }

    @Transactional
    public Lecture updateLecture(Long lectureId, LectureUpdateRequestDto requestDto) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의가 속한 과목의 선생님인지 확인
        Teacher currentTeacher = currentUserResolver.getTeacher();

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. Entity 업데이트
        lecture.update(requestDto.getTitle(), requestDto.getWeekNumber(), requestDto.getDescription());

        return lecture; // 변경 감지로 인해 save() 호출 불필요
    }

    @Transactional
    public void deleteLecture(Long lectureId) {
        // 1. 강의 정보 조회
        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND));

        // 2. 권한 확인: 현재 로그인한 사용자가 이 강의가 속한 과목의 선생님인지 확인
        Teacher currentTeacher = currentUserResolver.getTeacher();

        if (!lecture.getCourse().getTeacher().getId().equals(currentTeacher.getId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        // 3. 강의(lecture)를 참조하는 자식 테이블 먼저 삭제/참조 해제 (FK 제약 방지)
        learningDataCleanupService.deleteByLectureId(lectureId);
        generatedContentRepository.clearSessionByLectureId(lectureId);
        generationSessionRepository.deleteByLectureId(lectureId);
        examSessionRepository.deleteByLectureId(lectureId);
        examProfileRepository.deleteByLectureId(lectureId);
        materialRepository.deleteByLectureId(lectureId);

        // 4. 강의 삭제 (cascade: materials, generated_contents, student_inquiries)
        lectureRepository.delete(lecture);
    }

    private void validateLectureParticipant(Course course) {
        User currentUser = currentUserResolver.getUser();

        boolean isTeacherOfCourse = currentUser.getTeacher() != null
                && currentUser.getTeacher().getId().equals(course.getTeacher().getId());
        boolean isStudentEnrolled = currentUser.getStudent() != null
                && enrollmentRepository.existsByStudentAndCourse(currentUser.getStudent(), course);

        if (!isTeacherOfCourse && !isStudentEnrolled) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }


}
