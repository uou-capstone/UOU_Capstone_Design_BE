package io.github.uou_capstone.aiplatform.domain.user.repository;

import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, Long> {

    java.util.Optional<Student> findByUser_Id(Long userId);

    @Query("""
            SELECT s FROM Student s
            JOIN FETCH s.user
            WHERE s.id = :studentId
            """)
    java.util.Optional<Student> findByIdWithUser(@Param("studentId") Long studentId);
}
