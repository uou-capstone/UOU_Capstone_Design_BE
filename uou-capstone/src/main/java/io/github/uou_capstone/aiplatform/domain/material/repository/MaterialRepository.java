package io.github.uou_capstone.aiplatform.domain.material.repository;

import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findByLecture_IdAndMaterialType(Long lectureId, String materialType);

    List<Material> findByLecture_IdOrderByCreatedAtDesc(Long lectureId);

    List<Material> findByLecture_IdInOrderByLecture_IdAscCreatedAtDesc(java.util.Collection<Long> lectureIds);

    Optional<Material> findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(Long lectureId, String materialType);

    //이전 pdf 삭제 기능
    void deleteByLecture_IdAndMaterialType(Long lectureId, String materialType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Material m where m.lecture.id = :lectureId")
    void deleteByLectureId(@Param("lectureId") Long lectureId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Material m where m.lecture.course.id = :courseId")
    void deleteByLectureCourseId(@Param("courseId") Long courseId);
}