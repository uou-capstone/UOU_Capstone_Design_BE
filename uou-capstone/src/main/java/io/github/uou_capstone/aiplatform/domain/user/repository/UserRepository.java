package io.github.uou_capstone.aiplatform.domain.user.repository;

import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 이메일을 통해 사용자를 찾는 메서드
     * @param email 사용자 이메일
     * @return Optional<User>
     */
    Optional<User> findByEmail(String email);

    /**
     * 이메일로 사용자를 조회할 때 Student, Teacher를 함께 JOIN FETCH
     * 기존 3개 쿼리(user → student → teacher)를 1개 쿼리로 줄여줌
     */
    @EntityGraph(attributePaths = {"student", "teacher"})
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailWithRoles(@Param("email") String email);
}