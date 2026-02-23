package io.github.uou_capstone.aiplatform.domain.exam.entity;

import io.github.uou_capstone.aiplatform.domain.BaseTimeEntity;
import io.github.uou_capstone.aiplatform.domain.course.lecture.entity.Lecture;
import io.github.uou_capstone.aiplatform.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 시험 프로필
 * 사용자별 시험 프로필 캐싱 (Redis와 함께 사용)
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
    name = "exam_profiles",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_lecture", columnNames = {"user_id", "lecture_id"})
    }
)
public class ExamProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_json", nullable = false, columnDefinition = "JSON")
    private Map<String, Object> profileJson;  // TestProfile JSON

    @Column(name = "content_hash", length = 64)
    private String contentHash;  // 강의 내용 해시 (MD5) - Redis 캐시 키와 동일

    @Builder
    public ExamProfile(User user, Lecture lecture, Map<String, Object> profileJson, String contentHash) {
        this.user = user;
        this.lecture = lecture;
        this.profileJson = profileJson;
        this.contentHash = contentHash;
    }

    public void updateProfile(Map<String, Object> profileJson) {
        this.profileJson = profileJson;
    }

    public void updateContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
