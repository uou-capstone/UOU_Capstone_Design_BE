package io.github.uou_capstone.aiplatform.common.util;

/**
 * 알림 body 요약 유틸. 댓글·답글 알림에서 본문 markdown 일부를 Notification.body(1000자)에 맞춰 truncate.
 * 도메인 간 공유라 common 패키지에 둠.
 */
public final class NotificationBodyFormatter {

    private NotificationBodyFormatter() {
    }

    /**
     * 본문을 maxLen 자로 자르고 잘렸으면 말줄임표 부착. null 안전.
     */
    public static String summarize(String text, int maxLen) {
        if (text == null) return "";
        if (maxLen <= 0) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "…";
    }
}
