package com.ecommerce.platform.common.api;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore
) {

    public CursorPageResponse {
        items = List.copyOf(items);
        if (hasMore && (nextCursor == null || nextCursor.isBlank())) {
            throw new IllegalArgumentException("A continuation cursor is required when more rows exist");
        }
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("A terminal cursor page cannot expose a continuation cursor");
        }
    }
}
