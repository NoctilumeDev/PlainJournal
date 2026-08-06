package com.ecommerce.trade.application.port;

import java.util.List;

public interface DistributedIdVerifier {

    DistributedIdSnapshot generate(int count);

    record DistributedIdSnapshot(
            String instanceId,
            String namespace,
            int workerId,
            List<Long> ids) {
    }
}
