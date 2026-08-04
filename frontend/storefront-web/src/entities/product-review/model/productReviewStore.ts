import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createCatalogApi,
  secureRandomUUID,
  type BusinessId,
  type CatalogApi,
  type ProductReview,
  type ReviewEligibility,
  type ReviewReportReason,
  type ReviewSummary,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const LEGACY_PENDING_REVIEW_KEY = "plain-journal:pending-review:v1";
const PENDING_REVIEW_KEY_PREFIX = "plain-journal:pending-review:v2:";

export interface ReviewAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveReviewAccess {
  ownerId: BusinessId | null;
  accessToken: string | null;
  revision: number;
}

export interface PendingReviewSubmission {
  key: string;
  userId: BusinessId;
  orderNo: string;
  eligibilityId: BusinessId;
  productId: BusinessId;
  rating: number;
  content: string;
  anonymous: boolean;
  createdAt: string;
}

export interface ReviewSubmissionResult {
  reviewId: BusinessId;
  productId: BusinessId;
  recovered: boolean;
  review: ProductReview | null;
}

class ReviewResponseMismatchError extends Error {
  constructor(message = "Catalog 已响应，但返回的评价事实与本次请求不一致。") {
    super(message);
    this.name = "ReviewResponseMismatchError";
  }
}

function isAuthenticated(context: ReviewAccessContext): context is {
  authenticated: true;
  ownerId: BusinessId;
  accessToken: string;
} {
  return context.authenticated
    && typeof context.ownerId === "string"
    && context.ownerId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function scopedPendingKey(ownerId: BusinessId): string {
  return `${PENDING_REVIEW_KEY_PREFIX}${ownerId}`;
}

function parsePendingReview(
  raw: string | null,
  ownerId: BusinessId,
): PendingReviewSubmission | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("key" in value)
      || !("userId" in value)
      || !("orderNo" in value)
      || !("eligibilityId" in value)
      || !("productId" in value)
      || !("rating" in value)
      || !("content" in value)
      || !("anonymous" in value)
      || !("createdAt" in value)
      || typeof value.key !== "string"
      || value.userId !== ownerId
      || typeof value.orderNo !== "string"
      || typeof value.eligibilityId !== "string"
      || typeof value.productId !== "string"
      || typeof value.rating !== "number"
      || typeof value.content !== "string"
      || typeof value.anonymous !== "boolean"
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingReviewSubmission;
  } catch {
    return null;
  }
}

function loadPendingReview(ownerId: BusinessId): PendingReviewSubmission | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  const scoped = parsePendingReview(
    localStorage.getItem(scopedPendingKey(ownerId)),
    ownerId,
  );
  if (scoped) {
    return scoped;
  }
  const legacy = parsePendingReview(
    localStorage.getItem(LEGACY_PENDING_REVIEW_KEY),
    ownerId,
  );
  if (legacy) {
    localStorage.setItem(scopedPendingKey(ownerId), JSON.stringify(legacy));
    localStorage.removeItem(LEGACY_PENDING_REVIEW_KEY);
  }
  return legacy;
}

function reviewCommandKey(): string {
  return `review:${secureRandomUUID()}`;
}

function catalogApi(accessToken: string | null): CatalogApi {
  return createCatalogApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

function isUncertain(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}

function readError(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

export const useProductReviewsStore = defineStore("product-reviews", () => {
  const summaries = ref<Record<string, ReviewSummary>>({});
  const productReviews = ref<Record<string, ProductReview[]>>({});
  const eligibilities = ref<Record<string, ReviewEligibility[]>>({});
  const loadingProductId = ref<BusinessId | null>(null);
  const loadingOrderNo = ref<string | null>(null);
  const submittingEligibilityId = ref<BusinessId | null>(null);
  const actionReviewId = ref<BusinessId | null>(null);
  const productError = ref<string | null>(null);
  const eligibilityError = ref<string | null>(null);
  const submissionError = ref<string | null>(null);
  const participationError = ref<string | null>(null);
  const submissionUnknown = ref(false);
  const participationUnknownReviewId = ref<BusinessId | null>(null);
  const pendingReview = ref<PendingReviewSubmission | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let productRevision = 0;
  let eligibilityRevision = 0;
  let submissionRevision = 0;
  let participationRevision = 0;
  let activeSubmission: {
    accessRevision: number;
    fingerprint: string;
    promise: Promise<ReviewSubmissionResult | null>;
  } | null = null;
  let activeReaction: {
    accessRevision: number;
    reviewId: BusinessId;
    desired: boolean;
    promise: Promise<ProductReview | null>;
  } | null = null;
  let activeReport: {
    accessRevision: number;
    fingerprint: string;
    reviewId: BusinessId;
    promise: Promise<boolean>;
  } | null = null;

  const currentPending = computed(() => pendingReview.value);

  function synchronizeAccess(context: ReviewAccessContext): ActiveReviewAccess {
    const nextOwnerId = isAuthenticated(context) ? context.ownerId : null;
    const nextAccessToken = isAuthenticated(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;

    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      productRevision += 1;
      eligibilityRevision += 1;
      submissionRevision += 1;
      participationRevision += 1;
      activeSubmission = null;
      activeReaction = null;
      activeReport = null;
      summaries.value = {};
      productReviews.value = {};
      eligibilities.value = {};
      loadingProductId.value = null;
      loadingOrderNo.value = null;
      submittingEligibilityId.value = null;
      actionReviewId.value = null;
      productError.value = null;
      eligibilityError.value = null;
      submissionError.value = null;
      participationError.value = null;
      participationUnknownReviewId.value = null;
      pendingReview.value = nextOwnerId ? loadPendingReview(nextOwnerId) : null;
      submissionUnknown.value = Boolean(pendingReview.value);
      if (tokenChanged && !ownerChanged && pendingReview.value) {
        submissionError.value = "会话凭据已经更新，原评价键仍按当前账户保留；请重新查询 Catalog 事实。";
      }
    }

    return {
      ownerId: nextOwnerId,
      accessToken: nextAccessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveReviewAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireAuthenticated(
    context: ReviewAccessContext,
    fallback: string,
  ): ActiveReviewAccess | null {
    const access = synchronizeAccess(context);
    if (access.ownerId && access.accessToken) {
      return access;
    }
    participationError.value = fallback;
    return null;
  }

  function verifySummary(productId: BusinessId, summary: ReviewSummary) {
    if (summary.productId !== productId) {
      throw new ReviewResponseMismatchError("Catalog 返回了错误商品的评分汇总。");
    }
  }

  function verifyReview(
    review: ProductReview,
    expected: { productId?: BusinessId; reviewId?: BusinessId; published?: boolean },
  ) {
    if (
      (expected.productId && review.productId !== expected.productId)
      || (expected.reviewId && review.id !== expected.reviewId)
      || (expected.published && review.status !== "PUBLISHED")
      || !Number.isInteger(review.rating)
      || review.rating < 1
      || review.rating > 5
    ) {
      throw new ReviewResponseMismatchError();
    }
  }

  function verifyEligibility(orderNo: string, eligibility: ReviewEligibility) {
    if (
      eligibility.orderNo !== orderNo
      || (eligibility.status !== "ELIGIBLE" && eligibility.status !== "REVIEWED")
      || (eligibility.status === "REVIEWED" && !eligibility.reviewId)
      || (eligibility.status === "ELIGIBLE" && eligibility.reviewId)
    ) {
      throw new ReviewResponseMismatchError("Catalog 返回了错误订单或状态矛盾的评价资格。");
    }
  }

  function upsertReview(review: ProductReview) {
    const values = productReviews.value[review.productId] ?? [];
    const index = values.findIndex((candidate) => candidate.id === review.id);
    if (review.status !== "PUBLISHED") {
      productReviews.value[review.productId] = values.filter(
        (candidate) => candidate.id !== review.id,
      );
      return;
    }
    if (index >= 0) {
      const next = [...values];
      next[index] = review;
      productReviews.value[review.productId] = next;
    } else {
      productReviews.value[review.productId] = [review, ...values];
    }
  }

  function persistPending(
    access: ActiveReviewAccess,
    value: PendingReviewSubmission | null,
  ) {
    if (!accessIsCurrent(access) || !access.ownerId) {
      return;
    }
    pendingReview.value = value;
    if (typeof localStorage === "undefined") {
      return;
    }
    const key = scopedPendingKey(access.ownerId);
    if (value) {
      localStorage.setItem(key, JSON.stringify(value));
    } else {
      localStorage.removeItem(key);
    }
  }

  async function loadProductForAccess(
    access: ActiveReviewAccess,
    productId: BusinessId,
  ): Promise<ProductReview[]> {
    const requestRevision = ++productRevision;
    loadingProductId.value = productId;
    productError.value = null;
    try {
      const [summary, page] = await Promise.all([
        catalogApi(access.accessToken).reviewSummary(productId),
        catalogApi(access.accessToken).productReviews(productId, 1, 50),
      ]);
      if (!accessIsCurrent(access) || requestRevision !== productRevision) {
        return productReviews.value[productId] ?? [];
      }
      verifySummary(productId, summary);
      for (const review of page.items) {
        verifyReview(review, { productId, published: true });
      }
      summaries.value[productId] = summary;
      productReviews.value[productId] = page.items;
      return page.items;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === productRevision) {
        productError.value = readError(cause, "商品评价暂时无法读取。");
      }
      return productReviews.value[productId] ?? [];
    } finally {
      if (accessIsCurrent(access) && requestRevision === productRevision) {
        loadingProductId.value = null;
      }
    }
  }

  function loadProduct(
    context: ReviewAccessContext,
    productId: BusinessId,
  ): Promise<ProductReview[]> {
    return loadProductForAccess(synchronizeAccess(context), productId);
  }

  async function loadEligibilitiesForAccess(
    access: ActiveReviewAccess,
    orderNo: string,
    useCachedOnFailure = true,
  ): Promise<ReviewEligibility[]> {
    if (!access.ownerId || !access.accessToken) {
      eligibilities.value = {};
      eligibilityError.value = "登录后才能读取当前账户的评价资格。";
      return [];
    }
    const requestRevision = ++eligibilityRevision;
    loadingOrderNo.value = orderNo;
    eligibilityError.value = null;
    try {
      const values = await catalogApi(access.accessToken).reviewEligibilities(orderNo);
      if (!accessIsCurrent(access) || requestRevision !== eligibilityRevision) {
        return useCachedOnFailure ? (eligibilities.value[orderNo] ?? []) : [];
      }
      for (const eligibility of values) {
        verifyEligibility(orderNo, eligibility);
      }
      eligibilities.value[orderNo] = values;
      return values;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === eligibilityRevision) {
        eligibilityError.value = readError(cause, "评价资格暂时无法读取。");
      }
      return useCachedOnFailure ? (eligibilities.value[orderNo] ?? []) : [];
    } finally {
      if (accessIsCurrent(access) && requestRevision === eligibilityRevision) {
        loadingOrderNo.value = null;
      }
    }
  }

  function loadEligibilities(
    context: ReviewAccessContext,
    orderNo: string,
  ): Promise<ReviewEligibility[]> {
    return loadEligibilitiesForAccess(synchronizeAccess(context), orderNo);
  }

  function prepareSubmission(
    access: ActiveReviewAccess,
    orderNo: string,
    eligibility: ReviewEligibility,
    rating: number,
    rawContent: string,
    anonymous: boolean,
  ): PendingReviewSubmission | null {
    const content = rawContent.trim();
    if (!content) {
      submissionError.value = "请填写基于真实收货体验的评价内容。";
      return null;
    }
    if (!Number.isInteger(rating) || rating < 1 || rating > 5) {
      submissionError.value = "评分必须是 1 至 5 星。";
      return null;
    }
    if (eligibility.orderNo !== orderNo) {
      submissionError.value = "评价资格不属于当前订单。";
      return null;
    }
    if (eligibility.status !== "ELIGIBLE") {
      submissionError.value = "这条订单行已经评价，不能重复提交。";
      return null;
    }
    if (!access.ownerId) {
      submissionError.value = "当前会话没有可提交评价的顾客事实。";
      return null;
    }
    const existing = pendingReview.value;
    if (existing) {
      if (
        existing.orderNo !== orderNo
        || existing.eligibilityId !== eligibility.id
        || existing.productId !== eligibility.productId
        || existing.rating !== rating
        || existing.content !== content
        || existing.anonymous !== anonymous
      ) {
        submissionUnknown.value = true;
        submissionError.value = "当前账户已有另一条评价结果尚未确认，请先按原内容恢复。";
        return null;
      }
      return existing;
    }
    const pending: PendingReviewSubmission = {
      key: reviewCommandKey(),
      userId: access.ownerId,
      orderNo,
      eligibilityId: eligibility.id,
      productId: eligibility.productId,
      rating,
      content,
      anonymous,
      createdAt: new Date().toISOString(),
    };
    persistPending(access, pending);
    return pending;
  }

  async function recoverSubmission(
    access: ActiveReviewAccess,
    pending: PendingReviewSubmission,
  ): Promise<ReviewSubmissionResult | null> {
    const values = await loadEligibilitiesForAccess(access, pending.orderNo, false);
    if (!accessIsCurrent(access)) {
      return null;
    }
    const recovered = values.find((value) => value.id === pending.eligibilityId);
    if (
      recovered?.status !== "REVIEWED"
      || !recovered.reviewId
      || recovered.productId !== pending.productId
    ) {
      return null;
    }
    await loadProductForAccess(access, pending.productId);
    if (!accessIsCurrent(access)) {
      return null;
    }
    const review = (productReviews.value[pending.productId] ?? []).find(
      (value) => value.id === recovered.reviewId,
    ) ?? null;
    persistPending(access, null);
    submissionUnknown.value = false;
    submissionError.value = null;
    return {
      reviewId: recovered.reviewId,
      productId: pending.productId,
      recovered: true,
      review,
    };
  }

  function submit(
    context: ReviewAccessContext,
    orderNo: string,
    eligibility: ReviewEligibility,
    rating: number,
    content: string,
    anonymous: boolean,
  ): Promise<ReviewSubmissionResult | null> {
    const access = synchronizeAccess(context);
    if (!access.ownerId || !access.accessToken) {
      submissionError.value = "当前会话没有可提交评价的顾客事实。";
      return Promise.resolve(null);
    }
    const fingerprint = JSON.stringify([
      orderNo,
      eligibility.id,
      rating,
      content.trim(),
      anonymous,
    ]);
    if (activeSubmission) {
      if (
        activeSubmission.accessRevision === access.revision
        && activeSubmission.fingerprint === fingerprint
      ) {
        return activeSubmission.promise;
      }
      submissionUnknown.value = true;
      submissionError.value = "另一条评价正在确认，当前提交没有发出。";
      return Promise.resolve(null);
    }
    const request = submitForAccess(
      access,
      orderNo,
      eligibility,
      rating,
      content,
      anonymous,
    );
    activeSubmission = { accessRevision: access.revision, fingerprint, promise: request };
    const cleanup = () => {
      if (activeSubmission?.promise === request) {
        activeSubmission = null;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function submitForAccess(
    access: ActiveReviewAccess,
    orderNo: string,
    eligibility: ReviewEligibility,
    rating: number,
    content: string,
    anonymous: boolean,
  ): Promise<ReviewSubmissionResult | null> {
    submissionError.value = null;
    const hadPending = Boolean(pendingReview.value);
    const pending = prepareSubmission(
      access,
      orderNo,
      eligibility,
      rating,
      content,
      anonymous,
    );
    if (!pending) {
      return null;
    }
    if (hadPending) {
      const recovered = await recoverSubmission(access, pending);
      if (!accessIsCurrent(access) || recovered) {
        return recovered;
      }
    }

    const requestRevision = ++submissionRevision;
    submittingEligibilityId.value = eligibility.id;
    try {
      const review = await catalogApi(access.accessToken).createReview({
        eligibilityId: pending.eligibilityId,
        rating: pending.rating,
        content: pending.content,
        anonymous: pending.anonymous,
      }, pending.key);
      if (!accessIsCurrent(access) || requestRevision !== submissionRevision) {
        return null;
      }
      verifyReview(review, { productId: pending.productId, published: true });
      upsertReview(review);
      persistPending(access, null);
      submissionUnknown.value = false;
      submissionError.value = null;
      await Promise.all([
        loadEligibilitiesForAccess(access, orderNo),
        loadProductForAccess(access, pending.productId),
      ]);
      return {
        reviewId: review.id,
        productId: review.productId,
        recovered: false,
        review,
      };
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== submissionRevision) {
        return null;
      }
      if (isUncertain(cause)) {
        const recovered = await recoverSubmission(access, pending);
        if (recovered) {
          return recovered;
        }
        submissionUnknown.value = true;
        submissionError.value = "评价结果尚未确认。原幂等键和原内容已按当前账户保留。";
        return null;
      }
      if (cause instanceof ReviewResponseMismatchError) {
        submissionUnknown.value = true;
        submissionError.value = cause.message;
        return null;
      }
      persistPending(access, null);
      submissionUnknown.value = false;
      submissionError.value = readError(cause, "评价提交未完成。");
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === submissionRevision) {
        submittingEligibilityId.value = null;
      }
    }
  }

  function toggleLike(
    context: ReviewAccessContext,
    review: ProductReview,
  ): Promise<ProductReview | null> {
    const access = requireAuthenticated(context, "登录后才能标记评价是否有用。");
    if (!access) {
      return Promise.resolve(null);
    }
    const desired = !review.likedByViewer;
    if (activeReport) {
      participationError.value = "一条举报仍在确认，当前点赞操作没有发出。";
      return Promise.resolve(null);
    }
    if (activeReaction) {
      if (
        activeReaction.accessRevision === access.revision
        && activeReaction.reviewId === review.id
        && activeReaction.desired === desired
      ) {
        return activeReaction.promise;
      }
      participationError.value = "这条评价的上一项操作仍在确认，当前操作没有发出。";
      return Promise.resolve(null);
    }
    const request = toggleLikeForAccess(access, review, desired);
    activeReaction = {
      accessRevision: access.revision,
      reviewId: review.id,
      desired,
      promise: request,
    };
    const cleanup = () => {
      if (activeReaction?.promise === request) {
        activeReaction = null;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function toggleLikeForAccess(
    access: ActiveReviewAccess,
    review: ProductReview,
    desired: boolean,
  ): Promise<ProductReview | null> {
    const requestRevision = ++participationRevision;
    actionReviewId.value = review.id;
    participationError.value = null;
    participationUnknownReviewId.value = null;
    try {
      const value = desired
        ? await catalogApi(access.accessToken).likeReview(review.id)
        : await catalogApi(access.accessToken).unlikeReview(review.id);
      if (!accessIsCurrent(access) || requestRevision !== participationRevision) {
        return null;
      }
      verifyReview(value, {
        productId: review.productId,
        reviewId: review.id,
        published: true,
      });
      if (value.likedByViewer !== desired) {
        throw new ReviewResponseMismatchError("Catalog 返回的点赞状态与本次操作不一致。");
      }
      upsertReview(value);
      return value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === participationRevision) {
        participationError.value = readError(cause, "点赞状态未能确认。");
        participationUnknownReviewId.value = isUncertain(cause) ? review.id : null;
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === participationRevision) {
        actionReviewId.value = null;
      }
    }
  }

  function report(
    context: ReviewAccessContext,
    reviewId: BusinessId,
    reasonCode: ReviewReportReason,
    detail: string,
  ): Promise<boolean> {
    const access = requireAuthenticated(context, "登录后才能举报需要平台核对的评价。");
    if (!access) {
      return Promise.resolve(false);
    }
    const normalizedDetail = detail.trim();
    const fingerprint = JSON.stringify([reviewId, reasonCode, normalizedDetail]);
    if (activeReaction) {
      participationError.value = "一条点赞操作仍在确认，当前举报没有发出。";
      return Promise.resolve(false);
    }
    if (activeReport) {
      if (
        activeReport.accessRevision === access.revision
        && activeReport.fingerprint === fingerprint
      ) {
        return activeReport.promise;
      }
      participationError.value = "另一条举报仍在确认，当前举报没有发出。";
      return Promise.resolve(false);
    }
    const request = reportForAccess(
      access,
      reviewId,
      reasonCode,
      normalizedDetail,
    );
    activeReport = {
      accessRevision: access.revision,
      fingerprint,
      reviewId,
      promise: request,
    };
    const cleanup = () => {
      if (activeReport?.promise === request) {
        activeReport = null;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function reportForAccess(
    access: ActiveReviewAccess,
    reviewId: BusinessId,
    reasonCode: ReviewReportReason,
    detail: string,
  ): Promise<boolean> {
    const requestRevision = ++participationRevision;
    actionReviewId.value = reviewId;
    participationError.value = null;
    participationUnknownReviewId.value = null;
    try {
      const receipt = await catalogApi(access.accessToken).reportReview(
        reviewId,
        detail ? { reasonCode, detail } : { reasonCode },
      );
      if (!accessIsCurrent(access) || requestRevision !== participationRevision) {
        return false;
      }
      if (receipt.reviewId !== reviewId) {
        throw new ReviewResponseMismatchError("Catalog 返回的举报回执与本次评价不一致。");
      }
      return true;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === participationRevision) {
        participationError.value = isUncertain(cause)
          ? "举报结果尚未确认；接口没有客户端幂等键，当前页面不会自动重提。"
          : readError(cause, "举报事实未能保存。");
        participationUnknownReviewId.value = isUncertain(cause) ? reviewId : null;
      }
      return false;
    } finally {
      if (accessIsCurrent(access) && requestRevision === participationRevision) {
        actionReviewId.value = null;
      }
    }
  }

  return {
    summaries,
    productReviews,
    eligibilities,
    loadingProductId,
    loadingOrderNo,
    submittingEligibilityId,
    actionReviewId,
    productError,
    eligibilityError,
    submissionError,
    participationError,
    submissionUnknown,
    participationUnknownReviewId,
    pendingReview,
    currentPending,
    activeOwnerId,
    synchronizeAccess,
    loadProduct,
    loadEligibilities,
    submit,
    toggleLike,
    report,
  };
});
