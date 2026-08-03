package com.ecommerce.payment.application.port;

import java.math.BigDecimal;

public interface RefundChannelPort {

    void requestRefund(RefundRequest request);

    record RefundRequest(
            String refundNo,
            String paymentNo,
            String channel,
            BigDecimal amount
    ) {
    }
}
