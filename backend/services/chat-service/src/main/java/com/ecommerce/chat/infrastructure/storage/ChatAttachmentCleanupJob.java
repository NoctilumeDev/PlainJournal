package com.ecommerce.chat.infrastructure.storage;

import com.ecommerce.chat.application.service.ChatAttachmentCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.attachments",
        name = "cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAttachmentCleanupJob {

    private final ChatAttachmentCleanupService cleanupService;

    public ChatAttachmentCleanupJob(ChatAttachmentCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.chat.attachments.cleanup-initial-delay:30000}",
            fixedDelayString = "${ecommerce.chat.attachments.cleanup-fixed-delay:30000}")
    public void cleanup() {
        cleanupService.cleanupQuarantineBatch();
        cleanupService.cleanupBatch();
    }
}
