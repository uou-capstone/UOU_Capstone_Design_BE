package io.github.uou_capstone.aiplatform.domain.user.repository;

import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentRepository extends JpaRepository<Student, Long> {

    java.util.Optional<Student> findByUser_Id(Long userId);
}
