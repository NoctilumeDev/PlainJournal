package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.trade.application.model.TradeModels.PaymentContextView;
import com.ecommerce.trade.application.service.TradeOrderService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/trade/internal")
public class InternalTradeController {

    private final TradeOrderService orderService;

    public InternalTradeController(TradeOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders/{orderNo}/payment-context")
    public ApiResponse<PaymentContextView> paymentContext(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._:-]+") String orderNo) {
        return ApiResponse.success(orderService.getPaymentContext(orderNo));
    }
}
