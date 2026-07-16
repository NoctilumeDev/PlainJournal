package com.ecommerce.fulfillment.application.service;

import com.ecommerce.fulfillment.application.model.FulfillmentModels.OrderPaidCommand;
import org.springframework.stereotype.Service;

@Service
public class OrderPaidHandler {

    private final FulfillmentService fulfillmentService;

    public OrderPaidHandler(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    public void handle(OrderPaidCommand command) {
        fulfillmentService.createFromOrderPaid(command);
    }
}
