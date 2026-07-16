package com.ecommerce.trade.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.trade.application.exception.TradeError;
import com.ecommerce.trade.application.exception.TradeException;
import com.ecommerce.trade.application.model.TradeModels.CartItemView;
import com.ecommerce.trade.application.service.CatalogSnapshotService.ResolvedLine;
import com.ecommerce.trade.infrastructure.persistence.entity.CartItemEntity;
import com.ecommerce.trade.infrastructure.persistence.mapper.CartItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class CartService {

    private static final int MAX_CART_ITEMS = 100;

    private final CartItemMapper cartItemMapper;
    private final CatalogSnapshotService snapshotService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CartService(
            CartItemMapper cartItemMapper,
            CatalogSnapshotService snapshotService,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.cartItemMapper = cartItemMapper;
        this.snapshotService = snapshotService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public CartItemView putItem(Long userId, Long productId, Long skuId, long quantity, boolean selected) {
        ResolvedLine snapshot = snapshotService.resolveOne(productId, skuId, quantity);
        return transactionTemplate.execute(ignored -> {
            CartItemEntity item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                    .eq(CartItemEntity::getUserId, userId)
                    .eq(CartItemEntity::getSkuId, skuId));
            boolean created = item == null;
            Instant now = clock.instant();
            if (created) {
                Long count = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItemEntity>()
                        .eq(CartItemEntity::getUserId, userId));
                if (count >= MAX_CART_ITEMS) {
                    throw new TradeException(TradeError.CART_LIMIT_EXCEEDED);
                }
                item = new CartItemEntity();
                item.setId(IdWorker.getId());
                item.setUserId(userId);
                item.setSkuId(skuId);
                item.setCreatedAt(now);
            }
            item.setProductId(productId);
            item.setQuantity(quantity);
            item.setSelected(selected);
            item.setUpdatedAt(now);
            if (created) {
                cartItemMapper.insert(item);
            } else {
                cartItemMapper.updateById(item);
            }
            return view(item, snapshot);
        });
    }

    public List<CartItemView> listItems(Long userId) {
        return cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                        .eq(CartItemEntity::getUserId, userId)
                        .orderByDesc(CartItemEntity::getUpdatedAt))
                .stream()
                .map(item -> view(item, snapshotService.resolveOne(
                        item.getProductId(), item.getSkuId(), item.getQuantity())))
                .toList();
    }

    @Transactional
    public void removeItem(Long userId, Long skuId) {
        cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
    }

    private CartItemView view(CartItemEntity item, ResolvedLine snapshot) {
        return new CartItemView(
                item.getId(), item.getProductId(), item.getSkuId(), snapshot.productTitle(),
                snapshot.skuName(), snapshot.specJson(), snapshot.unitPrice(), item.getQuantity(), item.getSelected());
    }
}
