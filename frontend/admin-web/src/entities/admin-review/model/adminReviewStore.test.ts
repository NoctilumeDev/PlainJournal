import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

import type {
  ProductReview,
  ReviewModerationResult,
  ReviewReport,
} from "@plain-journal/foundation";

import {
  useAdminReviewStore,
  type AdminReviewAccessContext,
} from "./adminReviewStore";

const OPERATOR_ID = "2087000000000000001";
const REPORT_ID = "2087000000000000101";
const REVIEW_ID = "2087000000000000201";
const PRODUCT_ID = "2087000000000000301";
const ACCESS: AdminReviewAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "operator-token",
};
const UPDATED_ACCESS: AdminReviewAccessContext = {
  authorized: true,
  operatorId: OPERATOR_ID,
  accessToken: "operator-token-refreshed",
};
const OTHER_ACCESS: AdminReviewAccessContext = {
  authorized: true,
  operatorId: "2087000000000000002",
  accessToken: "other-token",
};
const STORAGE_KEY =
  `plain-journal:admin-review:pending-command:v1:${OPERATOR_ID}`;

function success(data: unknown): Response {
  return new Response(JSON.stringify({
    code: "OK",
    message: "success",
    data,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

function failure(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({
    code,
    message,
    data: null,
    timestamp: "2026-08-03T00:00:00Z",
  }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function reportFixture(
  overrides: Partial<ReviewReport> = {},
): ReviewReport {
  return {
    id: REPORT_ID,
    reviewId: REVIEW_ID,
    productId: PRODUCT_ID,
    rating: 2,
    reviewContent: "这条评价需要平台核对。",
    reasonCode: "FALSE_INFORMATION",
    detail: "与订单商品规格不一致。",
    status: "OPEN",
    resolution: null,
    createdAt: "2026-08-03T00:00:00Z",
    resolvedAt: null,
    ...overrides,
  };
}

function reviewFixture(
  overrides: Partial<ProductReview> = {},
): ProductReview {
  return {
    id: REVIEW_ID,
    productId: PRODUCT_ID,
    skuId: "2087000000000000401",
    skuName: "青灰",
    specJson: "{\"color\":\"青灰\"}",
    rating: 2,
    content: "这条评价需要平台核对。",
    anonymous: true,
    authorLabel: "Anonymous verified customer",
    status: "PUBLISHED",
    likeCount: 0,
    likedByViewer: false,
    reply: {
      id: "2087000000000000501",
      content: "平台已核对订单快照与商品规格。",
      createdAt: "2026-08-03T00:01:00Z",
    },
    createdAt: "2026-08-03T00:00:00Z",
    ...overrides,
  };
}

function moderationFixture(
  overrides: Partial<ReviewModerationResult> = {},
): ReviewModerationResult {
  return {
    reportId: REPORT_ID,
    reviewId: REVIEW_ID,
    commandId: "review-moderation:test-command",
    resolution: "UPHELD",
    reviewStatusBefore: "PUBLISHED",
    reviewStatusAfter: "HIDDEN",
    resolvedAt: "2026-08-03T00:02:00Z",
    ...overrides,
  };
}

function reportsPage(items = [reportFixture()]) {
  return {
    items,
    page: 1,
    size: 100,
    total: items.length,
  };
}

describe("admin review entity", () => {
  beforeEach(() => {
    localStorage.clear();
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it("loads OPEN reports with string identities and preserves known facts on a later 503", async () => {
    let attempts = 0;
    const requested: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      attempts += 1;
      requested.push(String(input));
      return attempts === 1
        ? success(reportsPage())
        : failure(503, "SERVICE_UNAVAILABLE", "catalog unavailable");
    }));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    await store.loadReports(ACCESS);
    await store.loadReports(ACCESS);

    expect(store.reports).toEqual([reportFixture()]);
    expect(store.loadError).toBe("catalog unavailable");
    expect(requested[0]).toContain(
      "/catalog/admin/reviews/reports?page=1&size=100&status=OPEN",
    );
    expect(store.reports[0]?.id).toBe(REPORT_ID);
  });

  it("keeps a lost reply unknown and retries the exact command id and content", async () => {
    const requests: Array<{ key: string | null; body: unknown }> = [];
    let attempts = 0;
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      requests.push({
        key: new Headers(init?.headers).get("Idempotency-Key"),
        body: JSON.parse(String(init?.body)),
      });
      return attempts === 1
        ? failure(503, "SERVICE_UNAVAILABLE", "reply response lost")
        : success(reviewFixture());
    }));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    const report = reportFixture();
    const form = store.reviewForm(report.id);
    form.replyContent = "平台已核对订单快照与商品规格。";
    const commandId = form.replyCommandId;

    await store.reply(report, ACCESS);
    expect(store.commandPhase).toBe("unknown");
    expect(store.pendingCommand?.commandId).toBe(commandId);
    expect(localStorage.getItem(STORAGE_KEY)).toContain(commandId);

    await store.retryPending(ACCESS);

    expect(requests).toEqual([
      {
        key: commandId,
        body: { content: "平台已核对订单快照与商品规格。" },
      },
      {
        key: commandId,
        body: { content: "平台已核对订单快照与商品规格。" },
      },
    ]);
    expect(store.commandPhase).toBe("accepted");
    expect(store.confirmedReplies[REVIEW_ID]?.id)
      .toBe("2087000000000000501");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("keeps a lost moderation unknown and accepts only the exact audit replay", async () => {
    const requests: unknown[] = [];
    let attempts = 0;
    let commandId = "";
    vi.stubGlobal("fetch", vi.fn(async (
      _input: RequestInfo | URL,
      init?: RequestInit,
    ) => {
      attempts += 1;
      requests.push(JSON.parse(String(init?.body)));
      return attempts === 1
        ? failure(503, "SERVICE_UNAVAILABLE", "moderation response lost")
        : success(moderationFixture({ commandId }));
    }));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    store.reports = [reportFixture()];
    store.total = 1;
    const form = store.reviewForm(REPORT_ID);
    form.resolution = "UPHELD";
    form.resolutionReason = "已核对订单快照和当前商品规格，举报成立。";
    commandId = form.moderationCommandId;

    await store.moderate(reportFixture(), ACCESS);
    expect(store.commandPhase).toBe("unknown");
    expect(store.reports).toHaveLength(1);

    await store.retryPending(ACCESS);

    expect(requests).toEqual([
      {
        commandId,
        resolution: "UPHELD",
        reason: "已核对订单快照和当前商品规格，举报成立。",
      },
      {
        commandId,
        resolution: "UPHELD",
        reason: "已核对订单快照和当前商品规格，举报成立。",
      },
    ]);
    expect(store.commandPhase).toBe("accepted");
    expect(store.commandMessage).toContain("PUBLISHED");
    expect(store.commandMessage).toContain("HIDDEN");
    expect(store.reports).toEqual([]);
    expect(store.total).toBe(0);
  });

  it("keeps a mismatched 2xx moderation result unknown and does not remove the report", async () => {
    let commandId = "";
    vi.stubGlobal("fetch", vi.fn(async () =>
      success(moderationFixture({
        commandId,
        reviewId: "2087000000000000999",
      }))));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    store.reports = [reportFixture()];
    store.total = 1;
    const form = store.reviewForm(REPORT_ID);
    form.resolution = "UPHELD";
    form.resolutionReason = "已核对订单快照和商品规格后确认举报成立。";
    commandId = form.moderationCommandId;

    await store.moderate(reportFixture(), ACCESS);

    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("审核命令身份");
    expect(store.reports).toEqual([reportFixture()]);
    expect(store.pendingCommand?.commandId).toBe(commandId);
  });

  it("distinguishes rejected moderation from review visibility and never republishes HIDDEN", async () => {
    let commandId = "";
    vi.stubGlobal("fetch", vi.fn(async () =>
      success(moderationFixture({
        commandId,
        resolution: "REJECTED",
        reviewStatusBefore: "HIDDEN",
        reviewStatusAfter: "HIDDEN",
      }))));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    store.reports = [reportFixture()];
    store.total = 1;
    const form = store.reviewForm(REPORT_ID);
    form.resolution = "REJECTED";
    form.resolutionReason = "举报证据不足，但评价此前已由其他举报隐藏。";
    commandId = form.moderationCommandId;

    await store.moderate(reportFixture(), ACCESS);

    expect(store.commandPhase).toBe("accepted");
    expect(store.commandMessage).toContain("没有把它重新发布");
    expect(store.commandMessage).not.toContain("扣回一次");
  });

  it("clears pending identity after an explicit idempotency conflict", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(409, "IDEMPOTENCY_CONFLICT", "command belongs to another report")));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    const form = store.reviewForm(REPORT_ID);
    form.replyContent = "平台已核对订单快照与商品规格。";

    await store.reply(reportFixture(), ACCESS);

    expect(store.commandPhase).toBe("rejected");
    expect(store.commandMessage).toBe("command belongs to another report");
    expect(store.pendingCommand).toBeNull();
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  it("restores an unresolved moderation with the original id, decision and reason", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const first = useAdminReviewStore();
    first.synchronizeAccess(ACCESS);
    const form = first.reviewForm(REPORT_ID);
    form.resolution = "UPHELD";
    form.resolutionReason = "冻结原始审核说明用于重启后的安全重放。";
    const commandId = form.moderationCommandId;
    await first.moderate(reportFixture(), ACCESS);

    setActivePinia(createPinia());
    const restored = useAdminReviewStore();
    restored.synchronizeAccess(ACCESS);
    const restoredForm = restored.reviewForm(REPORT_ID);

    expect(restored.commandPhase).toBe("unknown");
    expect(restored.pendingCommand?.commandId).toBe(commandId);
    expect(restoredForm).toMatchObject({
      resolution: "UPHELD",
      resolutionReason: "冻结原始审核说明用于重启后的安全重放。",
      moderationCommandId: commandId,
    });
  });

  it("does not write a completed reply after the access token changes", async () => {
    let resolveReply!: (response: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() =>
      new Promise<Response>((resolve) => {
        resolveReply = resolve;
      })));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    store.reviewForm(REPORT_ID).replyContent =
      "平台已核对订单快照与商品规格。";
    const request = store.reply(reportFixture(), ACCESS);
    store.synchronizeAccess(UPDATED_ACCESS);
    resolveReply(success(reviewFixture()));

    await expect(request).resolves.toBeNull();
    expect(store.confirmedReplies).toEqual({});
    expect(store.commandPhase).toBe("unknown");
    expect(store.commandMessage).toContain("会话凭据已更新");
  });

  it("isolates pending commands and report facts when the operator changes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      failure(503, "SERVICE_UNAVAILABLE", "response lost")));

    const store = useAdminReviewStore();
    store.synchronizeAccess(ACCESS);
    store.reports = [reportFixture()];
    store.total = 1;
    store.reviewForm(REPORT_ID).replyContent =
      "平台已核对订单快照与商品规格。";
    await store.reply(reportFixture(), ACCESS);
    const commandId = store.pendingCommand?.commandId;

    store.synchronizeAccess(OTHER_ACCESS);

    expect(store.reports).toEqual([]);
    expect(store.total).toBe(0);
    expect(store.pendingCommand).toBeNull();
    expect(store.commandPhase).toBe("idle");
    expect(localStorage.getItem(STORAGE_KEY)).toContain(commandId);
  });
});
