package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.api.CursorPageResponse;
import com.ecommerce.platform.common.api.PageResponse;
import com.ecommerce.trade.application.model.TradeModels.CartItemView;
import com.ecommerce.trade.application.model.TradeModels.CreateOrderCommand;
import com.ecommerce.trade.application.model.TradeModels.GuestBagItemCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderLineCommand;
import com.ecommerce.trade.application.model.TradeModels.OrderView;
import com.ecommerce.trade.application.service.CartService;
import com.ecommerce.trade.application.service.TradeOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/trade")
public class TradeController {

    private static final String BUSINESS_NO_PATTERN = "[A-Za-z0-9._:-]+";

    private final CartService cartService;
    private final TradeOrderService orderService;

    public TradeController(CartService cartService, TradeOrderService orderService) {
        this.cartService = cartService;
        this.orderService = orderService;
    }

    @PutMapping("/cart/items/{skuId}")
    public ApiResponse<CartItemView> putCartItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long skuId,
            @Valid @RequestBody PutCartItemRequest request) {
        return ApiResponse.success(cartService.putItem(
                userId(jwt), request.productId(), skuId, request.quantity(), request.selected()));
    }

    @GetMapping("/cart/items")
    public ApiResponse<List<CartItemView>> cartItems(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(cartService.listItems(userId(jwt)));
    }

    @DeleteMapping("/cart/items/{skuId}")
    public ApiResponse<Void> removeCartItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive Long skuId) {
        cartService.removeItem(userId(jwt), skuId);
        return ApiResponse.success(null);
    }

    @PostMapping("/cart/guest-merge")
    public ApiResponse<List<CartItemView>> mergeGuestBag(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @Size(min = 8, max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey,
            @Valid @RequestBody GuestBagMergeRequest request) {
        return ApiResponse.success(cartService.mergeGuestBag(
                userId(jwt),
                idempotencyKey,
                request.items().stream()
                        .map(item -> new GuestBagItemCommand(
                                item.productId(), item.skuId(), item.quantity()))
                        .toList()));
    }

    @PostMapping("/orders")
    public ApiResponse<OrderView> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key")
            @Size(min = 8, max = 64) @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        List<OrderLineCommand> items = request.items().stream()
                .map(item -> new OrderLineCommand(item.productId(), item.skuId(), item.quantity()))
                .toList();
        return ApiResponse.success(orderService.createOrder(
                new CreateOrderCommand(userId(jwt), idempotencyKey, request.addressId(), items,
                        request.benefitNos())));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderView>> orders(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(orderService.listOrders(userId(jwt), 1, 100).items());
    }

    @GetMapping("/orders/page")
    public ApiResponse<PageResponse<OrderView>> orderPage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size) {
        return ApiResponse.success(orderService.listOrders(userId(jwt), page, size));
    }

    @GetMapping("/orders/cursor")
    public ApiResponse<CursorPageResponse<OrderView>> orderCursor(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 200) String cursor) {
        return ApiResponse.success(orderService.listOrdersByCursor(
                userId(jwt), size, cursor));
    }

    @GetMapping("/orders/by-idempotency-key/{idempotencyKey}")
    public ApiResponse<OrderView> orderByIdempotencyKey(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Size(min = 8, max = 64)
            @Pattern(regexp = BUSINESS_NO_PATTERN) String idempotencyKey) {
        return ApiResponse.success(orderService.getOrderByIdempotencyKey(
                userId(jwt), idempotencyKey));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderView> order(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo) {
        return ApiResponse.success(orderService.getOrder(userId(jwt), orderNo));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<OrderView> cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = BUSINESS_NO_PATTERN) String orderNo) {
        return ApiResponse.success(orderService.cancelOrder(userId(jwt), orderNo));
    }

    private Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }

    public record PutCartItemRequest(
            @NotNull @Positive Long productId,
            @Positive @Max(1000000000L) long quantity,
            boolean selected
    ) {
    }

    public record GuestBagMergeRequest(
            @NotEmpty @Size(max = 100) List<@Valid GuestBagItemRequest> items
    ) {
    }

    public record GuestBagItemRequest(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long skuId,
            @Positive @Max(1000000000L) long quantity
    ) {
    }

    public record CreateOrderRequest(
            @NotNull @Positive Long addressId,
            @NotEmpty @Size(max = 20) List<@Valid OrderLineRequest> items,
            @Size(max = 3) List<@NotBlank @Size(max = 64)
                    @Pattern(regexp = BUSINESS_NO_PATTERN) String> benefitNos
    ) {
    }

    public record OrderLineRequest(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long skuId,
            @Positive @Max(1000000000L) long quantity
    ) {
    }
}
