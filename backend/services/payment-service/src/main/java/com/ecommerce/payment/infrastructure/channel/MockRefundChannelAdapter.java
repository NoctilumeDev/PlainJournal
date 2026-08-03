package com.ecommerce.payment.infrastructure.channel;

import com.ecommerce.payment.application.port.RefundChannelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MockRefundChannelAdapter implements RefundChannelPort {

    private static final Logger log = LoggerFactory.getLogger(MockRefundChannelAdapter.class);

    @Override
    public void requestRefund(RefundRequest request) {
        if (!"MOCK".equals(request.channel())) {
            throw new IllegalArgumentException("Unsupported refund channel: " + request.channel());
        }
        log.info("Mock refund request accepted and awaits an independent callback: refundNo={}",
                request.refundNo());
    }
}
