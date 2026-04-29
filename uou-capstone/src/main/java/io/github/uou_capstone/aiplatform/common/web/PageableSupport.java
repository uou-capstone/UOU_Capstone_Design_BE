package io.github.uou_capstone.aiplatform.common.web;

import io.github.uou_capstone.aiplatform.common.error.CommonErrorCode;
import io.github.uou_capstone.aiplatform.common.error.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * 컨트롤러 진입 시점의 Pageable 검증/정규화 헬퍼.
 * - size 최대 100 강제
 * - sort 화이트리스트 강제 (지정 외 필드는 INVALID_PARAMETER)
 * - 비어 있는 sort 일 때 default sort 적용
 */
public final class PageableSupport {

    public static final int MAX_PAGE_SIZE = 100;

    private PageableSupport() {
    }

    public static Pageable validate(Pageable raw, Set<String> allowedSortProps, Sort defaultSort) {
        if (raw.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                    "size 는 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
        if (raw.getPageNumber() < 0) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "page 는 0 이상이어야 합니다.");
        }

        Sort sort = raw.getSort();
        if (sort.isUnsorted()) {
            sort = defaultSort;
        } else {
            for (Sort.Order order : sort) {
                if (!allowedSortProps.contains(order.getProperty())) {
                    throw new BusinessException(CommonErrorCode.INVALID_PARAMETER,
                            "허용되지 않는 정렬 필드입니다: " + order.getProperty());
                }
            }
        }

        return PageRequest.of(raw.getPageNumber(), raw.getPageSize(), sort);
    }
}
