package com.thirdstone.commerce.platform.web;

import java.util.List;

/** One page of a list, with enough to render "page 2 of 7". */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T> PageResponse<T> of(List<T> items, PageRequest request, long total) {
        return new PageResponse<>(items, request.page(), request.size(), total);
    }
}
