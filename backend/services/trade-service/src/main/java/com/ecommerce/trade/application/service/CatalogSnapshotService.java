package com.ecommerce.trade.application.service;

import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.OrderLineCommand;
import com.ecommerce.trade.application.port.CatalogPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CatalogSnapshotService {

    private static final long MAX_QUANTITY = 1_000_000_000L;

    private final CatalogPort catalogPort;

    public CatalogSnapshotService(CatalogPort catalogPort) {
        this.catalogPort = catalogPort;
    }

    public ResolvedLine resolveOne(Long productId, Long skuId, long quantity) {
        return resolve(List.of(new OrderLineCommand(productId, skuId, quantity))).get(0);
    }

    public List<ResolvedLine> resolve(List<OrderLineCommand> requestedItems) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
        }
        Map<ItemKey, Long> quantities = new LinkedHashMap<>();
        for (OrderLineCommand item : requestedItems) {
            if (item.productId() == null || item.skuId() == null || item.quantity() <= 0) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
            }
            try {
                quantities.merge(new ItemKey(item.productId(), item.skuId()), item.quantity(), Math::addExact);
            } catch (ArithmeticException exception) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE, exception);
            }
        }
        if (quantities.values().stream().anyMatch(quantity -> quantity > MAX_QUANTITY)) {
            throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
        }

        Map<Long, CatalogPort.ProductSnapshot> products = new LinkedHashMap<>();
        List<ResolvedLine> resolved = new ArrayList<>();
        for (Map.Entry<ItemKey, Long> entry : quantities.entrySet()) {
            ItemKey key = entry.getKey();
            CatalogPort.ProductSnapshot product = products.computeIfAbsent(key.productId(), catalogPort::getProduct);
            if (!"ACTIVE".equals(product.status())) {
                throw new TradeException(TradeError.PRODUCT_UNAVAILABLE);
            }
            CatalogPort.SkuSnapshot sku = product.skus().stream()
                    .filter(candidate -> candidate.id().equals(key.skuId()) && "ACTIVE".equals(candidate.status()))
                    .findFirst()
                    .orElseThrow(() -> new TradeException(TradeError.PRODUCT_UNAVAILABLE));
            String imageObjectKey = product.media().stream()
                    .filter(media -> media.skuId() == null || media.skuId().equals(sku.id()))
                    .sorted(Comparator.comparingInt(CatalogPort.MediaSnapshot::sortOrder))
                    .map(CatalogPort.MediaSnapshot::objectKey)
                    .findFirst()
                    .orElse(null);
            resolved.add(new ResolvedLine(
                    product.id(), sku.id(), product.title(), sku.skuCode(), sku.name(), sku.specJson(),
                    imageObjectKey, sku.salePrice(), entry.getValue()));
        }
        return resolved.stream().sorted(Comparator.comparing(ResolvedLine::skuId)).toList();
    }

    private record ItemKey(Long productId, Long skuId) {
    }

    public record ResolvedLine(
            Long productId,
            Long skuId,
            String productTitle,
            String skuCode,
            String skuName,
            String specJson,
            String imageObjectKey,
            BigDecimal unitPrice,
            long quantity
    ) {
        public BigDecimal lineAmount() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
