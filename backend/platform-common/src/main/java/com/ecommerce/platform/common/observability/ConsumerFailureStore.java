package com.ecommerce.platform.common.observability;

import java.time.Instant;
import java.util.List;

public interface ConsumerFailureStore {

    long countByStatus(String status);

    Instant selectOldestActiveFailedAt();

    List<ConsumerFailureEntry> selectRecentActive(int limit);
}
