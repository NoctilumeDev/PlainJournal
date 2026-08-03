package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.CartItemView;
import com.ecommerce.trade.application.model.TradeModels.GuestBagItemCommand;
import com.ecommerce.trade.application.service.CatalogSnapshotService.ResolvedLine;
import com.ecommerce.trade.infrastructure.persistence.entity.CartItemEntity;
import com.ecommerce.trade.infrastructure.persistence.entity.CartMergeRequestEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.CartItemMapper;
import com.ecommerce.trade.infrastructure.persistence.mapper.CartMergeRequestMapper;
import com.ecommerce.trade.infrastructure.sharding.TradeShardRouter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CartService {

    private static final int MAX_CART_ITEMS = 100;
    private static final long MAX_CART_QUANTITY = 1_000_000_000L;

    private final CartItemMapper cartItemMapper;
    private final CartMergeRequestMapper cartMergeRequestMapper;
    private final CatalogSnapshotService snapshotService;
    private final TransactionTemplate transactionTemplate;
    private final TradeShardRouter shardRouter;

    public CartService(
            CartItemMapper cartItemMapper,
            CartMergeRequestMapper cartMergeRequestMapper,
            CatalogSnapshotService snapshotService,
            TransactionTemplate transactionTemplate,
            TradeShardRouter shardRouter) {
        this.cartItemMapper = cartItemMapper;
        this.cartMergeRequestMapper = cartMergeRequestMapper;
        this.snapshotService = snapshotService;
        this.transactionTemplate = transactionTemplate;
        this.shardRouter = shardRouter;
    }

    public CartItemView putItem(Long userId, Long productId, Long skuId, long quantity, boolean selected) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(
                    userId, () -> putItem(userId, productId, skuId, quantity, selected));
        }
        ResolvedLine snapshot = snapshotService.resolveOne(productId, skuId, quantity);
        return transactionTemplate.execute(ignored -> {
            Instant now = cartItemMapper.currentTime();
            lockUserCart(userId, now);
            CartItemEntity item = cartItemMapper.selectForUpdate(userId, skuId);
            boolean created = item == null;
            if (created) {
                requireCartCapacity(userId);
                item = new CartItemEntity();
                item.setId(IdWorker.getId());
                item.setUserId(userId);
                item.setSkuId(skuId);
                item.setCreatedAt(now);
            }
            item.setProductId(productId);
            applySnapshot(item, snapshot);
            item.setQuantity(quantity);
            item.setSelected(selected);
            item.setUpdatedAt(now);
            if (created) {
                cartItemMapper.insert(item);
            } else {
                requireUpdated(cartItemMapper.updateById(item));
            }
            return view(item);
        });
    }

    public List<CartItemView> mergeGuestBag(
            Long userId,
            String mergeKey,
            List<GuestBagItemCommand> requestedItems) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(
                    userId, () -> mergeGuestBag(userId, mergeKey, requestedItems));
        }
        List<GuestBagItemCommand> items = canonicalItems(requestedItems);
        Map<Long, ResolvedLine> snapshotsBySku = new LinkedHashMap<>();
        items.forEach(item -> snapshotsBySku.put(
                item.skuId(),
                snapshotService.resolveOne(item.productId(), item.skuId(), item.quantity())));
        String requestHash = requestHash(items);
        Instant now = cartItemMapper.currentTime();
        long requestId = IdWorker.getId();

        transactionTemplate.executeWithoutResult(ignored -> {
            lockUserCart(userId, now);
            CartMergeRequestEntity candidate = new CartMergeRequestEntity();
            candidate.setId(requestId);
            candidate.setUserId(userId);
            candidate.setMergeKey(mergeKey);
            candidate.setRequestHash(requestHash);
            candidate.setCreatedAt(now);
            cartMergeRequestMapper.insertOrKeepExisting(candidate);

            CartMergeRequestEntity request = cartMergeRequestMapper.selectByKeyForUpdate(userId, mergeKey);
            if (request == null) {
                throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
            }
            if (!constantEquals(request.getRequestHash(), requestHash)) {
                throw new TradeException(TradeError.IDEMPOTENCY_CONFLICT);
            }
            if (!Objects.equals(request.getId(), requestId)) {
                return;
            }

            long currentItemCount = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, userId));
            for (GuestBagItemCommand command : items) {
                CartItemEntity item = cartItemMapper.selectForUpdate(userId, command.skuId());
                if (item == null) {
                    if (currentItemCount >= MAX_CART_ITEMS) {
                        throw new TradeException(TradeError.CART_LIMIT_EXCEEDED);
                    }
                    item = new CartItemEntity();
                    item.setId(IdWorker.getId());
                    item.setUserId(userId);
                    item.setProductId(command.productId());
                    item.setSkuId(command.skuId());
                    applySnapshot(item, snapshotsBySku.get(command.skuId()));
                    item.setQuantity(command.quantity());
                    item.setSelected(true);
                    item.setCreatedAt(now);
                    item.setUpdatedAt(now);
                    cartItemMapper.insert(item);
                    currentItemCount++;
                    continue;
                }
                if (!Objects.equals(item.getProductId(), command.productId())) {
                    throw new TradeException(TradeError.INVALID_CART_MERGE);
                }
                applySnapshot(item, snapshotsBySku.get(command.skuId()));
                item.setQuantity(Math.min(MAX_CART_QUANTITY, item.getQuantity() + command.quantity()));
                item.setSelected(true);
                item.setUpdatedAt(now);
                requireUpdated(cartItemMapper.updateById(item));
            }
        });
        return listItems(userId);
    }

    public List<CartItemView> listItems(Long userId) {
        if (!shardRouter.isRouted()) {
            return shardRouter.executeForUser(userId, () -> listItems(userId));
        }
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                        .eq(CartItemEntity::getUserId, userId)
                        .orderByDesc(CartItemEntity::getUpdatedAt))
                .stream()
                .map(this::view)
                .toList();
    }

    public void removeItem(Long userId, Long skuId) {
        if (!shardRouter.isRouted()) {
            shardRouter.runForUser(userId, () -> removeItem(userId, skuId));
            return;
        }
        transactionTemplate.executeWithoutResult(ignored -> {
            lockUserCart(userId, cartItemMapper.currentTime());
            cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, userId)
                    .eq(CartItemEntity::getSkuId, skuId));
        });
    }

    private CartItemView view(CartItemEntity item) {
        if (!hasDisplaySnapshot(item)) {
            applySnapshot(item, snapshotService.resolveOne(
                    item.getProductId(), item.getSkuId(), item.getQuantity()));
        }
        return new CartItemView(
                item.getId(), item.getProductId(), item.getSkuId(), item.getProductTitle(),
                item.getSkuName(), item.getSpecJson(), item.getUnitPrice(),
                item.getQuantity(), item.getSelected());
    }

    private void applySnapshot(CartItemEntity item, ResolvedLine snapshot) {
        item.setProductTitle(snapshot.productTitle());
        item.setSkuName(snapshot.skuName());
        item.setSpecJson(snapshot.specJson());
        item.setUnitPrice(snapshot.unitPrice());
    }

    private boolean hasDisplaySnapshot(CartItemEntity item) {
        return item.getProductTitle() != null
                && item.getSkuName() != null
                && item.getSpecJson() != null
                && item.getUnitPrice() != null;
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
        }
    }

    private void lockUserCart(Long userId, Instant now) {
        cartMergeRequestMapper.ensureUserLock(userId, now);
        if (!Objects.equals(cartMergeRequestMapper.lockUser(userId), userId)) {
            throw new TradeException(TradeError.CONCURRENT_MODIFICATION);
        }
    }

    private void requireCartCapacity(Long userId) {
        Long count = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId));
        if (count >= MAX_CART_ITEMS) {
            throw new TradeException(TradeError.CART_LIMIT_EXCEEDED);
        }
    }

    private List<GuestBagItemCommand> canonicalItems(List<GuestBagItemCommand> requestedItems) {
        Set<Long> skuIds = new HashSet<>();
        for (GuestBagItemCommand item : requestedItems) {
            if (!skuIds.add(item.skuId())) {
                throw new TradeException(TradeError.INVALID_CART_MERGE);
            }
        }
        return requestedItems.stream()
                .sorted(Comparator.comparing(GuestBagItemCommand::skuId)
                        .thenComparing(GuestBagItemCommand::productId))
                .toList();
    }

    private String requestHash(List<GuestBagItemCommand> items) {
        StringBuilder canonical = new StringBuilder();
        items.forEach(item -> canonical
                .append(item.productId()).append(':')
                .append(item.skuId()).append(':')
                .append(item.quantity()).append(';'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
