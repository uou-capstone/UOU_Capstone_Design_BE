package io.github.uou_capstone.aiplatform.domain.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    // 강의 ID로 원본 자료(예: PDF)를 찾는 메서드 (간단한 예시)
    Optional<Material> findByLecture_IdAndMaterialType(Long lectureId, String materialType);
}