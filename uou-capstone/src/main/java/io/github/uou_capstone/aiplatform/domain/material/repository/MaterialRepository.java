package io.github.uou_capstone.aiplatform.domain.material.repository;

import io.github.uou_capstone.aiplatform.domain.material.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    Optional<Material> findByLecture_IdAndMaterialType(Long lectureId, String materialType);

    List<Material> findByLecture_IdOrderByCreatedAtDesc(Long lectureId);

    Optional<Material> findFirstByLecture_IdAndMaterialTypeOrderByCreatedAtDesc(Long lectureId, String materialType);

    //이전 pdf 삭제 기능
    void deleteByLecture_IdAndMaterialType(Long lectureId, String materialType);
}