package com.ecommerce.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ecommerce.catalog.application.exception.CatalogError;
import com.ecommerce.catalog.application.exception.CatalogException;
import com.ecommerce.catalog.application.model.ReviewModels.CreateReviewCommand;
import com.ecommerce.catalog.application.model.ReviewModels.ModerationResultView;
import com.ecommerce.catalog.application.model.ReviewModels.OrderCompletedEvent;
import com.ecommerce.catalog.application.model.ReviewModels.OrderLineSnapshot;
import com.ecommerce.catalog.application.model.ReviewModels.ProductReviewView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewEligibilityView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportReceipt;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewReportView;
import com.ecommerce.catalog.application.model.ReviewModels.ReviewSummaryView;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository.EligibilityState;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository.ModerationAuditState;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository.ReplyState;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository.ReportState;
import com.ecommerce.catalog.infrastructure.persistence.ProductReviewRepository.ReviewState;
import com.ecommerce.platform.common.api.PageResponse;
import com.ecommerce.platform.common.idempotency.PayloadFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ProductReviewService {

    private static final Set<String> REPORT_REASONS = Set.of(
            "SPAM", "ABUSE", "FALSE_INFORMATION", "OTHER");
    private static final Set<String> REPORT_STATUSES = Set.of("OPEN", "RESOLVED");
    private static final Set<String> RESOLUTIONS = Set.of("UPHELD", "REJECTED");

    private final ProductReviewRepository repository;

    public ProductReviewService(ProductReviewRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean acceptOrderCompleted(
            OrderCompletedEvent event,
            String consumerGroup) {
        validateOrderCompleted(event);
        Instant now = repository.currentTime();
        String payloadFingerprint = PayloadFingerprint.of(event);
        if (!repository.insertConsumed(event.eventId(), consumerGroup, payloadFingerprint, now)) {
            String storedFingerprint = repository.findConsumedFingerprint(
                    event.eventId(), consumerGroup);
            if (!PayloadFingerprint.matches(storedFingerprint, payloadFingerprint)) {
                throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
            }
            return false;
        }
        for (OrderLineSnapshot item : event.items()) {
            repository.insertEligibility(IdWorker.getId(), event, item, now);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<ReviewEligibilityView> listEligibilities(long userId, String orderNo) {
        return repository.listEligibilities(userId, normalizeOptional(orderNo));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductReviewView createReview(CreateReviewCommand command) {
        String content = requireText(command.content(), 2000);
        String idempotencyKey = requireText(command.idempotencyKey(), 64);
        if (command.userId() == null || command.userId() <= 0
                || command.eligibilityId() == null || command.eligibilityId() <= 0
                || command.rating() < 1 || command.rating() > 5) {
            throw new IllegalArgumentException("Review command is invalid");
        }
        String requestHash = hash(
                command.eligibilityId(),
                command.rating(),
                content,
                command.anonymous());

        ReviewState existing = repository.findReviewByIdempotency(
                command.userId(),
                idempotencyKey);
        if (existing != null) {
            return sameReviewRequest(existing, requestHash, command.userId());
        }

        EligibilityState eligibility = repository.findEligibilityForUpdate(
                command.eligibilityId(),
                command.userId());
        if (eligibility == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        existing = repository.findReviewByIdempotency(command.userId(), idempotencyKey);
        if (existing != null) {
            return sameReviewRequest(existing, requestHash, command.userId());
        }
        ReviewState eligibilityReview = repository.findReviewByEligibilityForUpdate(
                eligibility.id());
        if (eligibilityReview != null) {
            if (!idempotencyKey.equals(eligibilityReview.idempotencyKey())) {
                throw new CatalogException(CatalogError.REVIEW_ALREADY_SUBMITTED);
            }
            return sameReviewRequest(eligibilityReview, requestHash, command.userId());
        }
        if (!"ELIGIBLE".equals(eligibility.status())) {
            throw new CatalogException(CatalogError.REVIEW_ALREADY_SUBMITTED);
        }

        Instant now = repository.currentTime();
        long reviewId = IdWorker.getId();
        repository.insertReview(
                reviewId,
                eligibility,
                command.rating(),
                content,
                command.anonymous(),
                idempotencyKey,
                requestHash,
                now);
        repository.markEligibilityReviewed(eligibility.id(), now);
        repository.incrementSummary(eligibility.productId(), command.rating(), now);
        return requireReviewView(reviewId, command.userId());
    }

    @Transactional(readOnly = true)
    public ReviewSummaryView summary(long productId) {
        requireActiveProduct(productId);
        return repository.findSummary(productId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductReviewView> listPublished(
            long productId,
            Long viewerId,
            long page,
            long size) {
        requireActiveProduct(productId);
        long total = repository.countPublishedReviews(productId);
        long offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Review page is too large", exception);
        }
        List<ProductReviewView> items = repository.listPublishedReviews(
                productId,
                viewerId,
                offset,
                Math.toIntExact(size));
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional
    public ProductReviewView like(long userId, long reviewId) {
        ReviewState review = requirePublishedReviewForUpdate(reviewId);
        Instant now = repository.currentTime();
        if (repository.insertLike(reviewId, userId, now)) {
            repository.incrementLikeCount(reviewId, now);
        }
        return requireReviewView(review.id(), userId);
    }

    @Transactional
    public ProductReviewView unlike(long userId, long reviewId) {
        ReviewState review = requirePublishedReviewForUpdate(reviewId);
        Instant now = repository.currentTime();
        if (repository.deleteLike(reviewId, userId)) {
            repository.decrementLikeCount(reviewId, now);
        }
        return requireReviewView(review.id(), userId);
    }

    @Transactional
    public ReviewReportReceipt report(
            long reporterId,
            long reviewId,
            String reasonCode,
            String detail) {
        ReviewState review = requirePublishedReviewForUpdate(reviewId);
        if (review.userId() == reporterId) {
            throw new CatalogException(CatalogError.REVIEW_ACTION_NOT_ALLOWED);
        }
        String normalizedReason = requireText(reasonCode, 40).toUpperCase(Locale.ROOT);
        if (!REPORT_REASONS.contains(normalizedReason)) {
            throw new IllegalArgumentException("Unsupported review report reason");
        }
        String normalizedDetail = normalizeOptional(detail);
        if (normalizedDetail != null && normalizedDetail.length() > 500) {
            throw new IllegalArgumentException("Review report detail is too long");
        }
        String requestHash = hash(reviewId, normalizedReason, normalizedDetail);
        ReportState existing = repository.findReportByReporter(reviewId, reporterId);
        if (existing != null) {
            if (!requestHash.equals(existing.requestHash())) {
                throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
            }
            return new ReviewReportReceipt(
                    existing.id(),
                    existing.reviewId(),
                    existing.status(),
                    repository.currentTime());
        }
        Instant now = repository.currentTime();
        long reportId = IdWorker.getId();
        if (!repository.insertReport(
                reportId,
                reviewId,
                reporterId,
                normalizedReason,
                normalizedDetail,
                requestHash,
                now)) {
            ReportState raced = repository.findReportByReporter(reviewId, reporterId);
            if (raced == null || !requestHash.equals(raced.requestHash())) {
                throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
            }
            return new ReviewReportReceipt(
                    raced.id(),
                    raced.reviewId(),
                    raced.status(),
                    now);
        }
        return new ReviewReportReceipt(reportId, reviewId, "OPEN", now);
    }

    @Transactional
    public ProductReviewView reply(
            long operatorId,
            long reviewId,
            String commandId,
            String content) {
        String normalizedCommand = requireText(commandId, 64);
        String normalizedContent = requireText(content, 1000);
        String requestHash = hash(reviewId, normalizedContent);

        ReplyState existing = repository.findReplyByCommand(normalizedCommand);
        if (existing != null) {
            if (existing.reviewId() != reviewId
                    || existing.operatorId() != operatorId
                    || !requestHash.equals(existing.requestHash())) {
                throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
            }
            return requireReviewView(reviewId, operatorId);
        }

        requirePublishedReviewForUpdate(reviewId);
        existing = repository.findReplyByCommand(normalizedCommand);
        if (existing != null) {
            if (existing.reviewId() != reviewId
                    || existing.operatorId() != operatorId
                    || !requestHash.equals(existing.requestHash())) {
                throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
            }
            return requireReviewView(reviewId, operatorId);
        }
        ReplyState byReview = repository.findReplyByReview(reviewId);
        if (byReview != null) {
            throw new CatalogException(CatalogError.REVIEW_ACTION_NOT_ALLOWED);
        }
        Instant now = repository.currentTime();
        if (!repository.insertReply(
                IdWorker.getId(),
                reviewId,
                operatorId,
                normalizedContent,
                normalizedCommand,
                requestHash,
                now)) {
            throw new CatalogException(CatalogError.REVIEW_ACTION_NOT_ALLOWED);
        }
        return requireReviewView(reviewId, operatorId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewReportView> listReports(
            String requestedStatus,
            long page,
            long size) {
        String status = normalizeOptional(requestedStatus);
        if (status != null) {
            status = status.toUpperCase(Locale.ROOT);
            if (!REPORT_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Unsupported review report status");
            }
        }
        long total = repository.countReports(status);
        long offset = Math.multiplyExact(page - 1, size);
        return new PageResponse<>(
                repository.listReports(status, offset, Math.toIntExact(size)),
                page,
                size,
                total);
    }

    @Transactional
    public ModerationResultView resolveReport(
            long operatorId,
            long reportId,
            String commandId,
            String resolution,
            String reason) {
        String normalizedCommand = requireText(commandId, 64);
        String normalizedResolution = requireText(resolution, 20).toUpperCase(Locale.ROOT);
        String normalizedReason = requireText(reason, 500);
        if (!RESOLUTIONS.contains(normalizedResolution)) {
            throw new IllegalArgumentException("Unsupported review report resolution");
        }
        String requestHash = hash(reportId, normalizedResolution, normalizedReason);
        ModerationAuditState existing = repository.findModerationAudit(normalizedCommand);
        if (existing != null) {
            return sameModerationRequest(existing, operatorId, reportId, requestHash);
        }

        ReportState report = repository.findReportForUpdate(reportId);
        if (report == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        existing = repository.findModerationAudit(normalizedCommand);
        if (existing != null) {
            return sameModerationRequest(existing, operatorId, reportId, requestHash);
        }
        if (!"OPEN".equals(report.status())) {
            throw new CatalogException(CatalogError.REPORT_ALREADY_RESOLVED);
        }
        ReviewState review = repository.findReviewForUpdate(report.reviewId());
        if (review == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        String beforeStatus = review.status();
        String afterStatus = beforeStatus;
        Instant now = repository.currentTime();
        if ("UPHELD".equals(normalizedResolution)
                && "PUBLISHED".equals(beforeStatus)) {
            repository.hideReview(review.id(), now);
            repository.decrementSummary(review.productId(), review.rating(), now);
            afterStatus = "HIDDEN";
        }
        if (!repository.insertModerationAudit(
                IdWorker.getId(),
                normalizedCommand,
                report.id(),
                review.id(),
                operatorId,
                normalizedResolution,
                normalizedReason,
                requestHash,
                beforeStatus,
                afterStatus,
                now)) {
            ModerationAuditState raced = repository.findModerationAudit(normalizedCommand);
            if (raced != null) {
                return sameModerationRequest(raced, operatorId, reportId, requestHash);
            }
            throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
        }
        repository.resolveReport(report.id(), operatorId, normalizedResolution, now);
        return repository.moderationResult(
                repository.findModerationAudit(normalizedCommand));
    }

    private ProductReviewView sameReviewRequest(
            ReviewState existing,
            String requestHash,
            long viewerId) {
        if (!requestHash.equals(existing.requestHash())) {
            throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
        }
        return requireReviewView(existing.id(), viewerId);
    }

    private ModerationResultView sameModerationRequest(
            ModerationAuditState existing,
            long operatorId,
            long reportId,
            String requestHash) {
        if (existing.operatorId() != operatorId
                || existing.reportId() != reportId
                || !requestHash.equals(existing.requestHash())) {
            throw new CatalogException(CatalogError.IDEMPOTENCY_CONFLICT);
        }
        return repository.moderationResult(existing);
    }

    private ReviewState requirePublishedReviewForUpdate(long reviewId) {
        ReviewState review = repository.findReviewForUpdate(reviewId);
        if (review == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        if (!"PUBLISHED".equals(review.status())) {
            throw new CatalogException(CatalogError.REVIEW_NOT_PUBLISHED);
        }
        return review;
    }

    private ProductReviewView requireReviewView(long reviewId, Long viewerId) {
        ProductReviewView review = repository.findReviewView(reviewId, viewerId);
        if (review == null) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
        return review;
    }

    private void requireActiveProduct(long productId) {
        if (productId <= 0 || !repository.activeProductExists(productId)) {
            throw new CatalogException(CatalogError.RESOURCE_NOT_FOUND);
        }
    }

    private void validateOrderCompleted(OrderCompletedEvent event) {
        if (event == null
                || event.eventId() == null
                || event.eventId().isBlank()
                || event.eventId().length() > 36
                || event.orderNo() == null
                || event.orderNo().isBlank()
                || event.orderNo().length() > 64
                || event.userId() == null
                || event.userId() <= 0
                || event.completedAt() == null
                || event.items() == null
                || event.items().isEmpty()) {
            throw new IllegalArgumentException("OrderCompleted event is invalid");
        }
        Set<Integer> lines = new HashSet<>();
        for (OrderLineSnapshot item : event.items()) {
            if (item.lineNo() <= 0
                    || !lines.add(item.lineNo())
                    || item.productId() == null
                    || item.productId() <= 0
                    || item.skuId() == null
                    || item.skuId() <= 0
                    || item.quantity() <= 0
                    || invalidText(item.productTitle(), 160)
                    || invalidText(item.skuCode(), 64)
                    || invalidText(item.skuName(), 160)
                    || invalidText(item.specJson(), 2000)
                    || item.imageObjectKey() != null
                    && item.imageObjectKey().length() > 500) {
                throw new IllegalArgumentException("OrderCompleted item is invalid");
            }
        }
    }

    private boolean invalidText(String value, int maximumLength) {
        return value == null || value.isBlank() || value.length() > maximumLength;
    }

    private String requireText(String value, int maximumLength) {
        if (value == null) {
            throw new IllegalArgumentException("Required text is missing");
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Required text is invalid");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private String hash(Object... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object component : components) {
                String value = component == null ? "<null>" : component.toString();
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
