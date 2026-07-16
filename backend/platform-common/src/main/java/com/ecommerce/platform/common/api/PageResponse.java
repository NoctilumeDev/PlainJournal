package com.ecommerce.platform.common.api;

import java.util.List;

public record PageResponse<T>(List<T> items, long page, long size, long total) {

    public PageResponse {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("Invalid page metadata");
        }
    }
}
