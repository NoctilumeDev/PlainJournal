package com.ecommerce.marketing.application.model;

public record OrderLifecycleCommand(String eventId, String eventType, String orderNo) {
}
