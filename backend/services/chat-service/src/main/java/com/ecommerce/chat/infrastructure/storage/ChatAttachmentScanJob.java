package com.ecommerce.chat.infrastructure.storage;

import com.ecommerce.chat.application.service.ChatAttachmentScanService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ecommerce.chat.attachments.scan",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAttachmentScanJob {

    private final ChatAttachmentScanService scanService;

    public ChatAttachmentScanJob(ChatAttachmentScanService scanService) {
        this.scanService = scanService;
    }

    @Scheduled(
            initialDelayString = "${ecommerce.chat.attachments.scan.initial-delay:5000}",
            fixedDelayString = "${ecommerce.chat.attachments.scan.fixed-delay:2000}")
    public void scan() {
        scanService.scanBatch();
    }
}
