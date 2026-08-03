package com.ecommerce.payment.application.port;

import java.math.BigDecimal;
import java.time.Instant;

public interface TradePort {

    PaymentContext getPaymentContext(String orderNo);

    record PaymentContext(
            String orderNo,
            Long userId,
            String reservationNo,
            String paymentNo,
            String status,
            BigDecimal totalAmount,
            Instant paymentDeadline
    ) {
    }
}
