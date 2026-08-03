package com.ecommerce.platform.common.observability;

import java.time.Instant;
import java.util.List;

public interface BusinessProcessStore {

    List<BusinessProcessDefinition> definitions();

    long count(BusinessProcessDefinition definition);

    Instant oldestUpdatedAt(BusinessProcessDefinition definition);

    List<BusinessProcessEntry> selectOldestActive(int limit);
}
