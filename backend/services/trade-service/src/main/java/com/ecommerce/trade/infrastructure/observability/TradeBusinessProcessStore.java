package com.ecommerce.trade.infrastructure.observability;

import com.ecommerce.platform.common.observability.BusinessProcessDefinition;
import com.ecommerce.platform.common.observability.BusinessProcessEntry;
import com.ecommerce.platform.common.observability.BusinessProcessStore;
import com.ecommerce.trade.infrastructure.persistence.mapper.AfterSaleOrderMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.TradeOrderMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class TradeBusinessProcessStore implements BusinessProcessStore {

    private static final String ORDER = "ORDER";
    private static final String AFTER_SALE = "AFTER_SALE";
    private static final List<BusinessProcessDefinition> DEFINITIONS = List.of(
            new BusinessProcessDefinition(ORDER, "PENDING_STOCK"),
            new BusinessProcessDefinition(ORDER, "PAYMENT_CONFIRMING"),
            new BusinessProcessDefinition(ORDER, "CANCELING"),
            new BusinessProcessDefinition(ORDER, "PAYMENT_EXCEPTION"),
            new BusinessProcessDefinition(AFTER_SALE, "APPLIED"),
            new BusinessProcessDefinition(AFTER_SALE, "WAIT_RETURN"),
            new BusinessProcessDefinition(AFTER_SALE, "RETURNING"),
            new BusinessProcessDefinition(AFTER_SALE, "RECEIVED"),
            new BusinessProcessDefinition(AFTER_SALE, "REFUNDING"),
            new BusinessProcessDefinition(AFTER_SALE, "REFUND_FAILED"));

    private final TradeOrderMapper orderMapper;
    private final AfterSaleOrderMapper afterSaleMapper;

    public TradeBusinessProcessStore(TradeOrderMapper orderMapper, AfterSaleOrderMapper afterSaleMapper) {
        this.orderMapper = orderMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    @Override
    public List<BusinessProcessDefinition> definitions() {
        return DEFINITIONS;
    }

    @Override
    public long count(BusinessProcessDefinition definition) {
        return mapperDomain(definition)
                ? orderMapper.countByStatus(definition.status())
                : afterSaleMapper.countByStatus(definition.status());
    }

    @Override
    public Instant oldestUpdatedAt(BusinessProcessDefinition definition) {
        return mapperDomain(definition)
                ? orderMapper.selectOldestUpdatedAtByStatus(definition.status())
                : afterSaleMapper.selectOldestUpdatedAtByStatus(definition.status());
    }

    @Override
    public List<BusinessProcessEntry> selectOldestActive(int limit) {
        List<BusinessProcessEntry> entries = new ArrayList<>();
        entries.addAll(orderMapper.selectOldestActive(limit));
        entries.addAll(afterSaleMapper.selectOldestActive(limit));
        return entries.stream()
                .sorted(Comparator.comparing(BusinessProcessEntry::getLastUpdatedAt)
                        .thenComparing(BusinessProcessEntry::getReferenceNo))
                .limit(limit)
                .toList();
    }

    private boolean mapperDomain(BusinessProcessDefinition definition) {
        if (ORDER.equals(definition.domain())) {
            return true;
        }
        if (AFTER_SALE.equals(definition.domain())) {
            return false;
        }
        throw new IllegalArgumentException("Unsupported Trade process domain: " + definition.domain());
    }
}
