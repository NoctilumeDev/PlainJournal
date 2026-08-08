package com.ecommerce.trade.infrastructure.id;

import com.ecommerce.platform.common.id.DistributedIdGenerator;
import com.ecommerce.trade.application.port.DistributedIdVerifier;
import com.ecommerce.trade.infrastructure.config.DistributedIdProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("m7-id-verification")
@Service
public class DistributedIdVerificationService implements DistributedIdVerifier {

    private final DistributedIdGenerator generator;
    private final DistributedIdWorkerLeaseManager leaseManager;
    private final DistributedIdProperties properties;

    public DistributedIdVerificationService(
            DistributedIdGenerator generator,
            DistributedIdWorkerLeaseManager leaseManager,
            DistributedIdProperties properties) {
        this.generator = generator;
        this.leaseManager = leaseManager;
        this.properties = properties;
    }

    @Override
    public DistributedIdSnapshot generate(int count) {
        return new DistributedIdSnapshot(
                properties.instanceId(),
                properties.namespace(),
                leaseManager.workerId(),
                generator.nextIds(count));
    }
}
