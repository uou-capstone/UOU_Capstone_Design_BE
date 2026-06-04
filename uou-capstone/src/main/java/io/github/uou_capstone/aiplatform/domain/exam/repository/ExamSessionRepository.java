package io.github.uou_capstone.aiplatform.domain.exam.repository;

import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamSession;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamStatus;
import io.github.uou_capstone.aiplatform.domain.exam.entity.ExamType;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    @Modifying
    @Query("DELETE FROM ExamSession es WHERE es.lecture.course.id = :courseId")
    void deleteByLectureCourseId(@Param("courseId") Long courseId);

    @Modifying
    @Query("DELETE FROM ExamSession es WHERE es.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ExamSession es SET es.material = null WHERE es.material.id = :materialId")
    int clearMaterialReference(@Param("materialId") Long materialId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ExamSession es
            SET es.material = null
            WHERE es.material.id IN (
                SELECT m.id
                FROM Material m
                WHERE m.lecture.id = :lectureId
                  AND m.materialType = :materialType
            )
            """)
    int clearMaterialReferencesByLectureAndType(@Param("lectureId") Long lectureId,
                                                @Param("materialType") String materialType);

    List<ExamSession> findByLecture(Lecture lecture);

    List<ExamSession> findByLecture_IdIn(java.util.Collection<Long> lectureIds);

    List<ExamSession> findByLecture_Course_Id(Long courseId);
    
    List<ExamSession> findByLectureAndExamType(Lecture lecture, ExamType examType);
    
    List<ExamSession> findByStatus(ExamStatus status);
    
    @Query("SELECT es FROM ExamSession es WHERE es.lecture.id = :lectureId AND es.examType = :examType")
    List<ExamSession> findByLectureIdAndExamType(@Param("lectureId") Long lectureId, @Param("examType") ExamType examType);
}
