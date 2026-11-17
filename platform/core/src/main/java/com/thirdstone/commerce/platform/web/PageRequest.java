package com.thirdstone.commerce.platform.web;

/**
 * {@code ?page=0&size=20} on every list endpoint, clamped rather than rejected:
 * a client asking for a million rows gets the maximum page, not an error and not
 * a million rows.
 */
public record PageRequest(int page, int size) {

    public static final int MAX_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    public static PageRequest of(Integer page, Integer size) {
        int p = page == null ? 0 : Math.clamp(page, 0, MAX_PAGE);
        int s = size == null ? 20 : Math.clamp(size, 1, MAX_SIZE);
        return new PageRequest(p, s);
    }

    public int offset() {
        return page * size;
    }
}
