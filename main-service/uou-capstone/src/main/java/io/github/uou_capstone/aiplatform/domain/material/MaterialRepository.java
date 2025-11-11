package io.github.uou_capstone.aiplatform.domain.material;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    // 강의 ID로 원본 자료(예: PDF)를 찾는 메서드 (간단한 예시)
    Optional<Material> findByLecture_IdAndMaterialType(Long lectureId, String materialType);

    /**
     * 특정 강의 ID와 자료 타입을 기준으로 Material을 조회합니다.
     * (예: 1번 강의의 "PDF" 자료 찾기)
     * @param lectureId 강의 ID
     * @param materialType 자료 타입 (예: "PDF")
     * @return Optional<Material>
     */
    Optional<Material> findByLectureIdAndMaterialType(Long lectureId, String materialType);
}