package com.arthadhruva.riskengine.search;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** The one paginated response shape for every list/search endpoint. */
public record PageResult<T>(List<T> items, long total, int page, int size, boolean hasMore) {

    /** Hard ceiling on page size, enforced server-side regardless of what a client asks for. */
    public static final int MAX_PAGE_SIZE = 100;

    public static <E, T> PageResult<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getNumber(), page.getSize(), page.hasNext());
    }

    public static int boundedSize(int requested) {
        return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
    }
}
