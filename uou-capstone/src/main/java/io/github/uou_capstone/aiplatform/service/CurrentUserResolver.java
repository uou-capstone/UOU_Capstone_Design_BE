package io.github.uou_capstone.aiplatform.service;

import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.domain.user.entity.Student;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

/**
 * HTTP 요청당 한 번만 User/Student/Teacher를 DB에서 조회하고 캐싱.
 * @RequestScope 로 빈이 요청마다 새로 생성되므로 스레드 안전하다.
 * @EntityGraph 로 User+Student+Teacher를 단일 JOIN 쿼리로 가져온다.
 */
@Service
@RequestScope
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final UserRepository userRepository;

    private User cachedUser;

    public User getUser() {
        if (cachedUser == null) {
            String email = SecurityContextHolder.getContext().getAuthentication().getName();
            cachedUser = userRepository.findByEmailWithRoles(email)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
        }
        return cachedUser;
    }

    public Student getStudent() {
        Student student = getUser().getStudent();
        if (student == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }
        return student;
    }

    public Teacher getTeacher() {
        Teacher teacher = getUser().getTeacher();
        if (teacher == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }
        return teacher;
    }
}
