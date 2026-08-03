import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  ProductReview,
  ReviewEligibility,
  ReviewSummary,
} from "@plain-journal/foundation";

import {
  type ReviewAccessContext,
  useProductReviewsStore,
} from "./productReviewStore";

const accessA: ReviewAccessContext = {
  authenticated: true,
  ownerId: "1001",
  accessToken: "token-a",
};
const accessA2: ReviewAccessContext = {
  authenticated: true,
  ownerId: "1001",
  accessToken: "token-a2",
};
const accessB: ReviewAccessContext = {
  authenticated: true,
  ownerId: "1002",
  accessToken: "token-b",
};

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-01T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function eligibility(
  overrides: Partial<ReviewEligibility> = {},
): ReviewEligibility {
  return {
    id: "4001",
    orderNo: "ORD-1",
    lineNo: 1,
    productId: "2001",
    skuId: "3001",
    productTitle: "帆布通勤袋",
    skuCode: "BAG-1",
    skuName: "自然色 / 中号",
    specJson: "{}",
    imageObjectKey: null,
    quantity: 1,
    status: "ELIGIBLE",
    reviewId: null,
    completedAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function productReview(
  overrides: Partial<ProductReview> = {},
): ProductReview {
  return {
    id: "5001",
    productId: "2001",
    skuId: "3001",
    skuName: "自然色 / 中号",
    specJson: "{}",
    rating: 5,
    content: "真实收货体验。",
    anonymous: false,
    authorLabel: "已购顾客",
    status: "PUBLISHED",
    likeCount: 0,
    likedByViewer: false,
    reply: null,
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function summary(productId = "2001"): ReviewSummary {
  return {
    productId,
    reviewCount: 1,
    averageRating: 5,
    rating1Count: 0,
    rating2Count: 0,
    rating3Count: 0,
    rating4Count: 0,
    rating5Count: 1,
  };
}

function pathOf(input: RequestInfo | URL): string {
  return new URL(String(input), "http://local").pathname;
}

function installSuccessfulSubmissionFetch(postGate?: ReturnType<typeof deferred<Response>>) {
  let posts = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = pathOf(input);
    if (path === "/api/v1/catalog/reviews") {
      posts += 1;
      return postGate ? postGate.promise : success(productReview());
    }
    if (path === "/api/v1/catalog/review-eligibilities") {
      return success([eligibility({ status: "REVIEWED", reviewId: "5001" })]);
    }
    if (path.endsWith("/review-summary")) {
      return success(summary());
    }
    if (path.endsWith("/reviews")) {
      return success({ items: [productReview()], page: 1, size: 50, total: 1 });
    }
    throw new Error(`Unexpected request: ${path}`);
  }));
  return () => posts;
}

describe("product review owner facts", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
    setActivePinia(createPinia());
  });

  it("drops an old account eligibility response after the owner changes", async () => {
    const response = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(() => response.promise));
    const store = useProductReviewsStore();

    const loading = store.loadEligibilities(accessA, "ORD-1");
    store.synchronizeAccess(accessB);
    response.resolve(success([eligibility()]));

    await loading;
    expect(store.activeOwnerId).toBe("1002");
    expect(store.eligibilities).toEqual({});
    expect(store.loadingOrderNo).toBeNull();
  });

  it("rejects a public summary or review belonging to another product", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      return path.endsWith("/review-summary")
        ? success(summary("wrong-product"))
        : success({ items: [productReview()], page: 1, size: 50, total: 1 });
    }));
    const store = useProductReviewsStore();

    await store.loadProduct(accessA, "2001");

    expect(store.summaries).toEqual({});
    expect(store.productReviews).toEqual({});
    expect(store.productError).toContain("错误商品");
  });

  it("coalesces identical concurrent submissions and rejects a different payload", async () => {
    const postGate = deferred<Response>();
    const postCount = installSuccessfulSubmissionFetch(postGate);
    const store = useProductReviewsStore();
    const fact = eligibility();

    const first = store.submit(accessA, "ORD-1", fact, 5, "真实收货体验。", false);
    const duplicate = store.submit(accessA, "ORD-1", fact, 5, "真实收货体验。", false);
    const conflicting = await store.submit(accessA, "ORD-1", fact, 4, "另一份内容。", false);

    expect(postCount()).toBe(1);
    expect(conflicting).toBeNull();
    expect(store.submissionError).toContain("正在确认");
    postGate.resolve(success(productReview()));
    await expect(first).resolves.toMatchObject({ reviewId: "5001", recovered: false });
    await expect(duplicate).resolves.toMatchObject({ reviewId: "5001" });
    expect(postCount()).toBe(1);
  });

  it("recovers a lost create response from Catalog eligibility without a second POST", async () => {
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path === "/api/v1/catalog/reviews") {
        posts += 1;
        throw new TypeError("response lost");
      }
      if (path === "/api/v1/catalog/review-eligibilities") {
        return success([eligibility({ status: "REVIEWED", reviewId: "5001" })]);
      }
      if (path.endsWith("/review-summary")) {
        return success(summary());
      }
      if (path.endsWith("/reviews")) {
        return success({ items: [productReview()], page: 1, size: 50, total: 1 });
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();

    const result = await store.submit(
      accessA,
      "ORD-1",
      eligibility(),
      5,
      "真实收货体验。",
      false,
    );

    expect(result).toMatchObject({ reviewId: "5001", recovered: true });
    expect(posts).toBe(1);
    expect(store.submissionUnknown).toBe(false);
    expect(store.currentPending).toBeNull();
    expect(localStorage.getItem("plain-journal:pending-review:v2:1001")).toBeNull();
  });

  it("does not recover a lost submission from a cached eligibility when the fresh query fails", async () => {
    let eligibilityReads = 0;
    let posts = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path === "/api/v1/catalog/review-eligibilities") {
        eligibilityReads += 1;
        if (eligibilityReads === 1) {
          return success([eligibility({ status: "REVIEWED", reviewId: "5001" })]);
        }
        throw new TypeError("fresh eligibility query failed");
      }
      if (path === "/api/v1/catalog/reviews") {
        posts += 1;
        throw new TypeError("response lost");
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();
    await store.loadEligibilities(accessA, "ORD-1");

    const result = await store.submit(
      accessA,
      "ORD-1",
      eligibility(),
      5,
      "真实收货体验。",
      false,
    );

    expect(result).toBeNull();
    expect(posts).toBe(1);
    expect(eligibilityReads).toBe(2);
    expect(store.submissionUnknown).toBe(true);
    expect(store.currentPending).toMatchObject({ eligibilityId: "4001" });
  });

  it("keeps an unknown command owner-scoped and restores it only for that owner", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path === "/api/v1/catalog/reviews") {
        throw new TypeError("response lost");
      }
      if (path === "/api/v1/catalog/review-eligibilities") {
        return success([eligibility()]);
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();

    await store.submit(accessA, "ORD-1", eligibility(), 5, "真实收货体验。", false);
    expect(store.submissionUnknown).toBe(true);
    expect(localStorage.getItem("plain-journal:pending-review:v2:1001")).not.toBeNull();

    store.synchronizeAccess(accessB);
    expect(store.currentPending).toBeNull();
    store.synchronizeAccess(accessA);
    expect(store.currentPending).toMatchObject({ userId: "1001", eligibilityId: "4001" });
  });

  it("does not apply a like response that arrives after the token changes", async () => {
    const likeResponse = deferred<Response>();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path.endsWith("/review-summary")) {
        return success(summary());
      }
      if (path.endsWith("/reviews")) {
        return success({ items: [productReview()], page: 1, size: 50, total: 1 });
      }
      if (path.endsWith("/likes")) {
        return likeResponse.promise;
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();
    await store.loadProduct(accessA, "2001");

    const pending = store.toggleLike(accessA, productReview());
    store.synchronizeAccess(accessA2);
    likeResponse.resolve(success(productReview({ likedByViewer: true, likeCount: 1 })));

    await expect(pending).resolves.toBeNull();
    expect(store.productReviews).toEqual({});
    expect(store.activeOwnerId).toBe("1001");
  });

  it("rejects a mismatched like fact instead of updating the visible review", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path.endsWith("/review-summary")) {
        return success(summary());
      }
      if (path.endsWith("/reviews")) {
        return success({ items: [productReview()], page: 1, size: 50, total: 1 });
      }
      if (path.endsWith("/likes")) {
        return success(productReview({ id: "wrong-review", likedByViewer: true }));
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();
    await store.loadProduct(accessA, "2001");

    await expect(store.toggleLike(accessA, productReview())).resolves.toBeNull();
    expect(store.productReviews["2001"]?.[0]?.likedByViewer).toBe(false);
    expect(store.participationError).toContain("不一致");
  });

  it("does not interleave a report with an in-flight like command", async () => {
    const likeResponse = deferred<Response>();
    let reports = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path.endsWith("/likes")) {
        return likeResponse.promise;
      }
      if (path.endsWith("/reports")) {
        reports += 1;
        return Promise.resolve(success({
          id: "6001",
          reviewId: "5001",
          status: "OPEN",
          createdAt: "2026-08-01T00:00:00Z",
        }));
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();

    const like = store.toggleLike(accessA, productReview());
    await expect(
      store.report(accessA, "5001", "FALSE_INFORMATION", "需要核对"),
    ).resolves.toBe(false);
    expect(reports).toBe(0);
    expect(store.participationError).toContain("点赞操作仍在确认");
    likeResponse.resolve(success(productReview({ likedByViewer: true, likeCount: 1 })));
    await expect(like).resolves.toMatchObject({ likedByViewer: true });
  });

  it("coalesces identical reports and marks response loss unknown without auto retry", async () => {
    const reportResponse = deferred<Response>();
    let reports = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const path = pathOf(input);
      if (path.endsWith("/reports")) {
        reports += 1;
        return reportResponse.promise;
      }
      throw new Error(`Unexpected request: ${path}`);
    }));
    const store = useProductReviewsStore();

    const first = store.report(accessA, "5001", "FALSE_INFORMATION", "需要核对");
    const duplicate = store.report(accessA, "5001", "FALSE_INFORMATION", "需要核对");
    expect(reports).toBe(1);
    reportResponse.reject(new TypeError("response lost"));

    await expect(first).resolves.toBe(false);
    await expect(duplicate).resolves.toBe(false);
    expect(reports).toBe(1);
    expect(store.participationUnknownReviewId).toBe("5001");
    expect(store.participationError).toContain("不会自动重提");
  });
});
