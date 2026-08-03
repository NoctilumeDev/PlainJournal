package com.ecommerce.payment.infrastructure.observability;

import com.ecommerce.payment.infrastructure.persistence.mapper.RefundOrderMapper;
import com.ecommerce.platform.common.observability.BusinessProcessDefinition;
import com.ecommerce.platform.common.observability.BusinessProcessEntry;
import com.ecommerce.platform.common.observability.BusinessProcessStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class PaymentBusinessProcessStore implements BusinessProcessStore {

    private static final String REFUND = "REFUND";
    private static final String REFUND_DISPATCH = "REFUND_DISPATCH";
    private static final List<BusinessProcessDefinition> DEFINITIONS = List.of(
            new BusinessProcessDefinition(REFUND, "PROCESSING"),
            new BusinessProcessDefinition(REFUND, "FAILED"),
            new BusinessProcessDefinition(REFUND_DISPATCH, "NEEDS_ATTENTION"));

    private final RefundOrderMapper refundMapper;

    public PaymentBusinessProcessStore(RefundOrderMapper refundMapper) {
        this.refundMapper = refundMapper;
    }

    @Override
    public List<BusinessProcessDefinition> definitions() {
        return DEFINITIONS;
    }

    @Override
    public long count(BusinessProcessDefinition definition) {
        return REFUND.equals(definition.domain())
                ? refundMapper.countByStatus(definition.status())
                : refundMapper.countByRequestStatus(dispatchStatus(definition));
    }

    @Override
    public Instant oldestUpdatedAt(BusinessProcessDefinition definition) {
        return REFUND.equals(definition.domain())
                ? refundMapper.selectOldestUpdatedAtByStatus(definition.status())
                : refundMapper.selectOldestUpdatedAtByRequestStatus(dispatchStatus(definition));
    }

    @Override
    public List<BusinessProcessEntry> selectOldestActive(int limit) {
        return refundMapper.selectOldestActive(limit);
    }

    private String dispatchStatus(BusinessProcessDefinition definition) {
        if (!REFUND_DISPATCH.equals(definition.domain())) {
            throw new IllegalArgumentException("Unsupported Payment process domain: " + definition.domain());
        }
        return definition.status();
    }
}
