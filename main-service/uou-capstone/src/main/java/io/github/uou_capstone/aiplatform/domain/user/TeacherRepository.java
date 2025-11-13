package io.github.uou_capstone.aiplatform.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {


    Optional<Teacher> findByUser_Id(Long userId);

}
