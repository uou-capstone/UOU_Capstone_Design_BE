package io.github.uou_capstone.aiplatform.common.dto;

import lombok.Getter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;

/**
 * FE/BE 공통 페이지 응답.
 * shape: content / page / size / totalElements / totalPages / first / last
 */
@Getter
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean first;
    private final boolean last;

    private PageResponse(List<T> content, int page, int size, long totalElements,
                         int totalPages, boolean first, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.first = first;
        this.last = last;
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }

    /**
     * In-memory slice. 호출부가 이미 정렬된 전체 리스트를 가지고 있을 때 사용.
     * size=0 인 Pageable 은 허용하지 않으므로 호출 전에 PageableSupport.validate 통과 필수.
     */
    public static <T> PageResponse<T> ofSlice(List<T> all, Pageable pageable) {
        int total = all.size();
        int size = pageable.getPageSize();
        int page = pageable.getPageNumber();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<T> slice = from >= to ? Collections.emptyList() : all.subList(from, to);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / size);
        boolean first = page == 0;
        boolean last = to >= total;
        return new PageResponse<>(slice, page, size, total, totalPages, first, last);
    }

    public static <T> PageResponse<T> empty(Pageable pageable) {
        return new PageResponse<>(
                List.of(),
                pageable.getPageNumber(),
                pageable.getPageSize(),
                0L,
                0,
                true,
                true
        );
    }
}
