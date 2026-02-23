package io.github.uou_capstone.aiplatform.util;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;

/**
 * 권한 확인 유틸리티 클래스
 * 
 * 주요 기능:
 * 1. 선생님 권한 확인
 * 2. 학생 권한 확인
 * 3. 강의 소유권 확인
 * 4. 현재 로그인한 사용자 정보 조회
 */
@Slf4j
public class AuthorizationUtil {

    /**
     * 현재 로그인한 사용자가 선생님인지 확인
     * 
     * @param user 사용자 엔티티
     * @throws BusinessException 사용자가 선생님이 아닌 경우
     */
    public static void requireTeacher(User user) {
        if (user == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }
        
        if (user.getRole() != Role.TEACHER) {
            log.warn("선생님 권한이 필요한 작업에 학생이 접근 시도: userId={}, role={}", 
                    user.getId(), user.getRole());
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "선생님 권한이 필요합니다.");
        }
    }

    /**
     * 현재 로그인한 사용자가 학생인지 확인
     * 
     * @param user 사용자 엔티티
     * @throws BusinessException 사용자가 학생이 아닌 경우
     */
    public static void requireStudent(User user) {
        if (user == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }
        
        if (user.getRole() != Role.STUDENT) {
            log.warn("학생 권한이 필요한 작업에 선생님이 접근 시도: userId={}, role={}", 
                    user.getId(), user.getRole());
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "학생 권한이 필요합니다.");
        }
    }

    /**
     * 현재 로그인한 사용자가 해당 강의의 소유자인지 확인
     * 
     * @param user 사용자 엔티티
     * @param lecture 강의 엔티티
     * @throws BusinessException 사용자가 강의 소유자가 아닌 경우
     */
    public static void requireLectureOwner(User user, Lecture lecture) {
        if (user == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }
        
        if (lecture == null) {
            throw new BusinessException(CommonErrorCode.LECTURE_NOT_FOUND);
        }

        // 선생님 권한 확인
        requireTeacher(user);

        // 강의의 소유자 확인 (Course의 teacher와 비교)
        if (lecture.getCourse() == null || lecture.getCourse().getTeacher() == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "강의 정보가 올바르지 않습니다.");
        }

        Long lectureOwnerId = lecture.getCourse().getTeacher().getUser().getId();
        if (!user.getId().equals(lectureOwnerId)) {
            log.warn("강의 소유자가 아닌 사용자가 접근 시도: userId={}, lectureId={}, ownerId={}", 
                    user.getId(), lecture.getId(), lectureOwnerId);
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "해당 강의의 소유자만 접근할 수 있습니다.");
        }
    }

    /**
     * 현재 로그인한 사용자가 해당 사용자 본인인지 확인
     * 
     * @param currentUser 현재 로그인한 사용자
     * @param targetUserId 확인할 사용자 ID
     * @throws BusinessException 사용자가 본인이 아닌 경우
     */
    public static void requireSelfOrTeacher(User currentUser, Long targetUserId) {
        if (currentUser == null) {
            throw new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND);
        }

        // 본인이거나 선생님인 경우 허용
        if (currentUser.getId().equals(targetUserId)) {
            return; // 본인
        }

        if (currentUser.getRole() == Role.TEACHER) {
            return; // 선생님은 모든 학생 정보 조회 가능
        }

        // 그 외의 경우 거부
        log.warn("본인 또는 선생님이 아닌 사용자가 다른 사용자 정보 접근 시도: currentUserId={}, targetUserId={}", 
                currentUser.getId(), targetUserId);
        throw new BusinessException(CommonErrorCode.FORBIDDEN, "본인 또는 선생님만 접근할 수 있습니다.");
    }

    /**
     * 현재 로그인한 사용자의 이메일 조회
     * 
     * @return 사용자 이메일
     * @throws BusinessException 인증 정보가 없는 경우
     */
    public static String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || authentication.getName() == null || 
            "anonymousUser".equals(authentication.getName())) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED, "인증되지 않은 사용자입니다.");
        }
        
        return authentication.getName();
    }

    /**
     * 현재 로그인한 사용자의 권한 확인
     * 
     * @param requiredRole 필요한 권한
     * @return 권한이 있는지 여부
     */
    public static boolean hasAuthority(Role requiredRole) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null) {
            return false;
        }

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        String requiredAuthority = requiredRole.name();
        
        return authorities.stream()
                .anyMatch(authority -> authority.getAuthority().equals(requiredAuthority));
    }

    /**
     * 현재 로그인한 사용자의 ID 조회 (이메일 기반)
     * 
     * @param userRepository UserRepository (의존성 주입 필요)
     * @return 사용자 ID
     * @throws BusinessException 사용자를 찾을 수 없는 경우
     */
    public static Long getCurrentUserId(io.github.uou_capstone.aiplatform.domain.user.repository.UserRepository userRepository) {
        String email = getCurrentUserEmail();
        return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.MEMBER_NOT_FOUND));
    }
}
