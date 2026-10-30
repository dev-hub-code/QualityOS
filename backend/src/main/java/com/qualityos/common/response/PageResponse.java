package com.qualityos.common.response;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Stable pagination payload, so API clients are not coupled to Spring Data's {@link Page} JSON. */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(
                items, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
