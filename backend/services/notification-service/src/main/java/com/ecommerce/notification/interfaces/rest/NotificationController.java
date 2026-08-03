package com.ecommerce.notification.interfaces.rest;

import com.ecommerce.notification.application.model.NotificationModels.DeliveryRetryView;
import com.ecommerce.notification.application.model.NotificationModels.EmailPreferenceView;
import com.ecommerce.notification.application.model.NotificationModels.NotificationView;
import com.ecommerce.notification.application.service.NotificationApplicationService;
import com.ecommerce.platform.common.api.ApiResponse;
import com.ecommerce.platform.common.api.CursorPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationApplicationService service;

    public NotificationController(NotificationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CursorPageResponse<NotificationView>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.list(userId(jwt), cursor, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(new UnreadCountResponse(service.unreadCount(userId(jwt))));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long notificationId) {
        service.markRead(userId(jwt), notificationId);
        return ApiResponse.success(null);
    }

    @PutMapping("/email-preference")
    public ApiResponse<EmailPreferenceView> saveEmailPreference(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EmailPreferenceRequest request) {
        return ApiResponse.success(service.saveEmailPreference(
                userId(jwt),
                request.email(),
                request.enabled()));
    }

    @PostMapping("/admin/email-deliveries/{deliveryId}/retry")
    public ApiResponse<DeliveryRetryView> retryEmailDelivery(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long deliveryId,
            @Valid @RequestBody DeliveryRetryRequest request) {
        return ApiResponse.success(service.retryEmailDelivery(
                userId(jwt),
                deliveryId,
                request.commandId(),
                request.reason()));
    }

    private long userId(Jwt jwt) {
        try {
            long value = Long.parseLong(jwt.getSubject());
            if (value <= 0) {
                throw new IllegalArgumentException("JWT subject must be positive");
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("JWT subject is invalid", exception);
        }
    }

    public record UnreadCountResponse(long count) {
    }

    public record EmailPreferenceRequest(
            @Email @Size(max = 190) String email,
            boolean enabled) {

        public EmailPreferenceRequest {
            if (enabled && (email == null || email.isBlank())) {
                throw new IllegalArgumentException(
                        "Email is required when email notifications are enabled");
            }
        }
    }

    public record DeliveryRetryRequest(
            @NotBlank @Size(max = 64) String commandId,
            @NotBlank @Size(min = 8, max = 500) String reason) {
    }
}
