package io.github.uou_capstone.aiplatform.domain.course.notice.repository;

import io.github.uou_capstone.aiplatform.domain.course.entity.Course;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.Notice;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeCategory;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticeComment;
import io.github.uou_capstone.aiplatform.domain.course.notice.entity.NoticePriority;
import io.github.uou_capstone.aiplatform.domain.user.entity.Role;
import io.github.uou_capstone.aiplatform.domain.user.entity.Teacher;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모 댓글 삭제 시 자식 답글이 DB cascade 로 함께 삭제되는지 검증.
 *
 * <p>{@code @OnDelete(action = CASCADE)} 가 NoticeComment.parentComment 매핑에 붙어 있어
 * Hibernate 가 H2 DDL 생성 시 ON DELETE CASCADE FK 를 박는다. 이 테스트는 그 동작이 운영 MySQL DDL 과
 * 일치함을 보장.
 *
 * <p>주의: {@code repository.delete(parent)} 직후 1차 캐시에는 자식이 아직 살아있다.
 * 반드시 {@code em.flush(); em.clear();} 후 재조회해야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Import(NoticeCommentCascadeTest.NoMetricsConfig.class)
class NoticeCommentCascadeTest {

    @Autowired NoticeCommentRepository commentRepository;
    @Autowired NoticeRepository noticeRepository;

    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("부모 댓글 삭제 → 자식 답글 cascade 삭제 (flush/clear 후 재조회)")
    void parent_delete_cascades_to_child() {
        // setup
        User teacherUser = User.builder()
                .email("t@x.com").password("p").fullName("teacher").role(Role.TEACHER).build();
        em.persist(teacherUser);

        Teacher teacher = Teacher.builder().schoolName("s").department("d").user(teacherUser).build();
        em.persist(teacher);

        Course course = Course.builder()
                .teacher(teacher).title("c").description("d").invitationCode("uniq-cascade").build();
        em.persist(course);

        Notice notice = Notice.builder()
                .course(course).author(teacher).title("n").contentMarkdown("body")
                .category(NoticeCategory.GENERAL).priority(NoticePriority.NORMAL).pinned(false).build();
        em.persist(notice);

        NoticeComment parent = NoticeComment.builder()
                .notice(notice).author(teacherUser).contentMarkdown("parent").build();
        em.persist(parent);

        NoticeComment child = NoticeComment.builder()
                .notice(notice).author(teacherUser).parentComment(parent).contentMarkdown("child").build();
        em.persist(child);

        em.flush();
        Long childId = child.getId();
        Long parentId = parent.getId();

        // 1차 캐시 비우고 detached 상태에서 삭제 (in-memory cascade 간섭 회피)
        em.clear();

        // when — 부모 삭제 (DB ON DELETE CASCADE 가 자식까지 정리)
        commentRepository.deleteById(parentId);
        em.flush();
        em.clear();

        // then — 둘 다 사라짐 (DB cascade)
        assertThat(commentRepository.findById(parentId)).isEmpty();
        assertThat(commentRepository.findById(childId)).isEmpty();
    }

    /** Spring Boot Actuator 빈을 @DataJpaTest 슬라이스에서 끔. */
    @org.springframework.boot.test.context.TestConfiguration
    static class NoMetricsConfig {
    }
}
