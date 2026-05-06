package io.github.uou_capstone.aiplatform.domain.course.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class JoinRequestBulkResultDto {

    private final int successCount;
    private final int failureCount;
    private final List<Item> results;

    public JoinRequestBulkResultDto(List<Item> results) {
        this.results = results;
        this.successCount = (int) results.stream().filter(Item::isSuccess).count();
        this.failureCount = results.size() - this.successCount;
    }

    @Getter
    public static class Item {
        private final Long requestId;
        private final boolean success;
        private final String errorCode;

        private Item(Long requestId, boolean success, String errorCode) {
            this.requestId = requestId;
            this.success = success;
            this.errorCode = errorCode;
        }

        public static Item success(Long requestId) {
            return new Item(requestId, true, null);
        }

        public static Item failure(Long requestId, String errorCode) {
            return new Item(requestId, false, errorCode);
        }
    }
}
