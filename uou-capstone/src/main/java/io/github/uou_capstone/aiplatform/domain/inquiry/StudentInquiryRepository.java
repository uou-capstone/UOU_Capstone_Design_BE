package io.github.uou_capstone.aiplatform.domain.inquiry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StudentInquiryRepository extends JpaRepository<StudentInquiry, Integer> {

    long countByLecture_Course_IdAndStudent_Id(Long courseId, Long studentId);

    @Query("""
            SELECT si.lecture.id, COUNT(si)
            FROM StudentInquiry si
            WHERE si.lecture.course.id = :courseId
            GROUP BY si.lecture.id
            """)
    List<Object[]> countByLectureForCourse(@Param("courseId") Long courseId);

    @Query("""
            SELECT si.lecture.id, si.student.id
            FROM StudentInquiry si
            WHERE si.lecture.course.id = :courseId
            """)
    List<Object[]> findLectureStudentPairsByCourse(@Param("courseId") Long courseId);
}
