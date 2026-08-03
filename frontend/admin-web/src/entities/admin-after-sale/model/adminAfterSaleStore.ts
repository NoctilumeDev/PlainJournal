import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createTradeApi,
  type AfterSale,
  type AfterSaleItem,
  type BusinessId,
  type TradeApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_STORAGE_PREFIX =
  "plain-journal:admin-after-sale:pending-review:v1:";

export const ADMIN_AFTER_SALE_STATUSES = [
  "APPLIED",
  "WAIT_RETURN",
  "RETURNING",
  "RECEIVED",
  "REFUNDING",
  "REFUND_FAILED",
  "COMPLETED",
  "REJECTED",
  "CANCELED",
] as const;

export type AdminAfterSaleStatus =
  typeof ADMIN_AFTER_SALE_STATUSES[number];

export type AfterSaleReviewPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

export interface AdminAfterSaleAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: TradeApi;
}

interface ReviewForm {
  approved: boolean;
  reason: string;
}

interface PendingAfterSaleReview {
  referenceNo: string;
  approved: boolean;
  reason: string;
  createdAt: string;
}

const APPROVED_PATH_STATUSES = new Set<AdminAfterSaleStatus>([
  "WAIT_RETURN",
  "RETURNING",
  "RECEIVED",
  "REFUNDING",
  "REFUND_FAILED",
  "COMPLETED",
]);

export class AfterSaleAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的售后请求结果不会写入当前页面。");
    this.name = "AfterSaleAccessChangedError";
  }
}

export class AfterSaleContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AfterSaleContractError";
  }
}

function isActiveContext(
  context: AdminAfterSaleAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && typeof context.operatorId === "string"
    && context.operatorId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function createApi(accessToken: string): TradeApi {
  return createTradeApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_STORAGE_PREFIX}${operatorId}`;
}

function parsePending(raw: string | null): PendingAfterSaleReview | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("referenceNo" in value)
      || typeof value.referenceNo !== "string"
      || value.referenceNo.length === 0
      || !("approved" in value)
      || typeof value.approved !== "boolean"
      || !("reason" in value)
      || typeof value.reason !== "string"
      || value.reason.length === 0
      || !("createdAt" in value)
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingAfterSaleReview;
  } catch {
    return null;
  }
}

function loadPending(
  operatorId: BusinessId,
): PendingAfterSaleReview | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(localStorage.getItem(storageKey(operatorId)));
}

function savePending(
  operatorId: BusinessId,
  value: PendingAfterSaleReview | null,
) {
  if (typeof localStorage === "undefined") {
    return;
  }
  if (value) {
    localStorage.setItem(storageKey(operatorId), JSON.stringify(value));
  } else {
    localStorage.removeItem(storageKey(operatorId));
  }
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
}

function isBusinessNo(value: unknown): value is string {
  return typeof value === "string"
    && /^[A-Za-z0-9._:-]+$/u.test(value);
}

function isMoney(value: unknown): value is string | number {
  return (typeof value === "string" && /^\d+(?:\.\d{1,2})?$/u.test(value))
    || (typeof value === "number" && Number.isFinite(value) && value >= 0);
}

function isInstant(value: unknown): value is string {
  return typeof value === "string"
    && value.length > 0
    && Number.isFinite(Date.parse(value));
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

function isAfterSaleStatus(value: unknown): value is AdminAfterSaleStatus {
  return typeof value === "string"
    && ADMIN_AFTER_SALE_STATUSES.includes(value as AdminAfterSaleStatus);
}

function validItem(value: unknown): value is AfterSaleItem {
  return Boolean(
    value
    && typeof value === "object"
    && "lineNo" in value
    && Number.isInteger(value.lineNo)
    && Number(value.lineNo) > 0
    && "skuId" in value
    && isBusinessId(value.skuId)
    && "productTitle" in value
    && typeof value.productTitle === "string"
    && value.productTitle.length > 0
    && "skuName" in value
    && typeof value.skuName === "string"
    && value.skuName.length > 0
    && "quantity" in value
    && Number.isInteger(value.quantity)
    && Number(value.quantity) > 0
    && "lineAmount" in value
    && isMoney(value.lineAmount)
    && "discountAmount" in value
    && isMoney(value.discountAmount)
    && "refundableAmount" in value
    && isMoney(value.refundableAmount),
  );
}

function validAfterSale(value: unknown): value is AfterSale {
  return Boolean(
    value
    && typeof value === "object"
    && "afterSaleNo" in value
    && isBusinessNo(value.afterSaleNo)
    && "orderNo" in value
    && isBusinessNo(value.orderNo)
    && "userId" in value
    && isBusinessId(value.userId)
    && "afterSaleType" in value
    && typeof value.afterSaleType === "string"
    && value.afterSaleType.length > 0
    && "status" in value
    && isAfterSaleStatus(value.status)
    && "reason" in value
    && typeof value.reason === "string"
    && value.reason.length > 0
    && "reviewReason" in value
    && (value.reviewReason === null || typeof value.reviewReason === "string")
    && "refundAmount" in value
    && isMoney(value.refundAmount)
    && "returnReceiptNo" in value
    && (value.returnReceiptNo === null || isBusinessNo(value.returnReceiptNo))
    && "refundNo" in value
    && (value.refundNo === null || isBusinessNo(value.refundNo))
    && "items" in value
    && Array.isArray(value.items)
    && value.items.length > 0
    && value.items.every(validItem)
    && new Set(value.items.map((item) => item.lineNo)).size
      === value.items.length
    && "version" in value
    && Number.isInteger(value.version)
    && Number(value.version) >= 0
    && "createdAt" in value
    && isInstant(value.createdAt)
    && "updatedAt" in value
    && isInstant(value.updatedAt)
    && "approvedAt" in value
    && isNullableInstant(value.approvedAt)
    && "completedAt" in value
    && isNullableInstant(value.completedAt),
  );
}

function validateAfterSale(
  value: unknown,
  expectedNo?: string,
): AfterSale {
  if (
    !validAfterSale(value)
    || (expectedNo !== undefined && value.afterSaleNo !== expectedNo)
  ) {
    throw new AfterSaleContractError(
      "Trade 售后事实缺少稳定字符串身份、状态或不可变商品快照。",
    );
  }
  return value;
}

function validateAfterSales(
  value: unknown,
  expectedStatus: string,
): AfterSale[] {
  if (
    !Array.isArray(value)
    || !value.every(validAfterSale)
    || new Set(value.map((item) => item.afterSaleNo)).size !== value.length
    || (
      expectedStatus
      && value.some((item) => item.status !== expectedStatus)
    )
  ) {
    throw new AfterSaleContractError(
      "Trade 售后列表与当前筛选、字符串身份或商品快照契约不一致。",
    );
  }
  return value;
}

function resultMayBeUnknown(cause: unknown): boolean {
  if (cause instanceof AfterSaleContractError) {
    return true;
  }
  if (!(cause instanceof ApiError)) {
    return true;
  }
  return cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500);
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

function decisionLabel(command: PendingAfterSaleReview): string {
  return command.approved ? "审核通过" : "审核拒绝";
}

function directResultMatches(
  command: PendingAfterSaleReview,
  value: AfterSale,
): boolean {
  const expectedStatus = command.approved ? "WAIT_RETURN" : "REJECTED";
  return value.afterSaleNo === command.referenceNo
    && value.status === expectedStatus
    && value.reviewReason === command.reason
    && (command.approved ? value.approvedAt !== null : true);
}

function authorityMatches(
  command: PendingAfterSaleReview,
  value: AfterSale,
): boolean {
  if (value.reviewReason !== command.reason) {
    return false;
  }
  if (command.approved) {
    return APPROVED_PATH_STATUSES.has(value.status as AdminAfterSaleStatus)
      && value.approvedAt !== null;
  }
  return value.status === "REJECTED";
}

export const useAdminAfterSaleStore = defineStore(
  "admin-after-sale",
  () => {
    const afterSales = ref<AfterSale[]>([]);
    const status = ref("");
    const loading = ref(false);
    const loadError = ref<string | null>(null);
    const refreshedAt = ref<string | null>(null);
    const reviewForms = reactive<Record<string, ReviewForm>>({});
    const reviewPhase = ref<AfterSaleReviewPhase>("idle");
    const reviewMessage = ref<string | null>(null);
    const pendingReview = ref<PendingAfterSaleReview | null>(null);
    const submitting = ref(false);
    const retryAllowed = ref(false);
    const activeOperatorId = ref<BusinessId | null>(null);
    let activeAccessToken: string | null = null;
    let accessRevision = 0;
    let factsRevision = 0;
    let commandRevision = 0;
    let activeCommandPromise: Promise<AfterSale | null> | null = null;

    const commandBlocked = computed(() =>
      submitting.value
      || (reviewPhase.value === "unknown" && pendingReview.value !== null));
    const canRetryPending = computed(() =>
      reviewPhase.value === "unknown"
      && pendingReview.value !== null
      && retryAllowed.value
      && !submitting.value);
    const pendingReferenceNo = computed(() =>
      pendingReview.value?.referenceNo ?? null);
    const pendingDecision = computed(() =>
      pendingReview.value ? decisionLabel(pendingReview.value) : null);

    function activeAccess(
      context: AdminAfterSaleAccessContext,
    ): ActiveAccess | null {
      if (!isActiveContext(context)) {
        return null;
      }
      return {
        operatorId: context.operatorId,
        accessToken: context.accessToken,
        revision: accessRevision,
        api: createApi(context.accessToken),
      };
    }

    function accessIsCurrent(access: ActiveAccess): boolean {
      return access.revision === accessRevision
        && access.operatorId === activeOperatorId.value
        && access.accessToken === activeAccessToken;
    }

    function requireCurrent(access: ActiveAccess) {
      if (!accessIsCurrent(access)) {
        throw new AfterSaleAccessChangedError();
      }
    }

    function clearForms() {
      for (const key of Object.keys(reviewForms)) {
        delete reviewForms[key];
      }
    }

    function hydratePending(command: PendingAfterSaleReview | null) {
      if (!command) {
        return;
      }
      reviewForms[command.referenceNo] = {
        approved: command.approved,
        reason: command.reason,
      };
    }

    function synchronizeAccess(context: AdminAfterSaleAccessContext) {
      const nextOperatorId = isActiveContext(context)
        ? context.operatorId
        : null;
      const nextAccessToken = isActiveContext(context)
        ? context.accessToken
        : null;
      const operatorChanged = activeOperatorId.value !== nextOperatorId;
      const accessChanged = operatorChanged
        || activeAccessToken !== nextAccessToken;
      if (!accessChanged) {
        return activeAccess(context);
      }

      activeOperatorId.value = nextOperatorId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      factsRevision += 1;
      commandRevision += 1;
      loading.value = false;
      submitting.value = false;
      retryAllowed.value = false;
      activeCommandPromise = null;

      if (operatorChanged) {
        afterSales.value = [];
        status.value = "";
        loadError.value = null;
        refreshedAt.value = null;
        clearForms();
        pendingReview.value = nextOperatorId
          ? loadPending(nextOperatorId)
          : null;
        hydratePending(pendingReview.value);
        reviewPhase.value = pendingReview.value ? "unknown" : "idle";
        reviewMessage.value = pendingReview.value
          ? "发现一条尚未确认的售后审核。原决定与原因已恢复；必须先读取 Trade 权威事实，不能直接显示成功或提交第二条审核。"
          : null;
      } else if (pendingReview.value) {
        reviewPhase.value = "unknown";
        reviewMessage.value =
          "员工会话凭据已更新，原售后审核继续保持结果未知；请重新读取 Trade 权威事实。";
      }
      return activeAccess(context);
    }

    function requireAccess(
      context: AdminAfterSaleAccessContext,
      message: string,
    ): ActiveAccess | null {
      const access = synchronizeAccess(context);
      if (!access) {
        reviewPhase.value = "rejected";
        reviewMessage.value = message;
      }
      return access;
    }

    function reviewForm(referenceNo: string): ReviewForm {
      reviewForms[referenceNo] ??= {
        approved: true,
        reason: "",
      };
      return reviewForms[referenceNo];
    }

    function upsertAfterSale(value: AfterSale) {
      const index = afterSales.value.findIndex((item) =>
        item.afterSaleNo === value.afterSaleNo);
      const visible = !status.value || value.status === status.value;
      if (index >= 0 && visible) {
        afterSales.value[index] = value;
      } else if (index >= 0) {
        afterSales.value.splice(index, 1);
      } else if (visible) {
        afterSales.value.unshift(value);
      }
    }

    async function loadFacts(
      context: AdminAfterSaleAccessContext,
    ): Promise<void> {
      const access = synchronizeAccess(context);
      if (!access) {
        loadError.value = "当前会话无权读取售后审核工作区。";
        return;
      }
      const expectedStatus = status.value;
      const requestRevision = ++factsRevision;
      loading.value = true;
      loadError.value = null;
      try {
        const values = validateAfterSales(
          await access.api.adminAfterSales(expectedStatus || undefined),
          expectedStatus,
        );
        requireCurrent(access);
        if (requestRevision !== factsRevision) {
          return;
        }
        afterSales.value = values;
        refreshedAt.value = new Date().toISOString();
      } catch (cause) {
        if (accessIsCurrent(access) && requestRevision === factsRevision) {
          loadError.value = errorMessage(
            cause,
            "Trade 售后列表暂时无法读取。",
          );
        }
      } finally {
        if (accessIsCurrent(access) && requestRevision === factsRevision) {
          loading.value = false;
        }
      }
    }

    function beginPending(
      access: ActiveAccess,
      command: PendingAfterSaleReview,
    ) {
      pendingReview.value = command;
      savePending(access.operatorId, command);
      retryAllowed.value = false;
      reviewPhase.value = "processing";
      reviewMessage.value =
        `售后 ${command.referenceNo} 的${decisionLabel(command)}正在等待 Trade 确认。`;
    }

    function settleAccepted(
      access: ActiveAccess,
      message: string,
    ) {
      const referenceNo = pendingReview.value?.referenceNo;
      pendingReview.value = null;
      savePending(access.operatorId, null);
      retryAllowed.value = false;
      reviewPhase.value = "accepted";
      reviewMessage.value = message;
      if (referenceNo) {
        delete reviewForms[referenceNo];
      }
    }

    function settleRejected(
      access: ActiveAccess,
      message: string,
    ) {
      pendingReview.value = null;
      savePending(access.operatorId, null);
      retryAllowed.value = false;
      reviewPhase.value = "rejected";
      reviewMessage.value = message;
    }

    async function executePending(
      access: ActiveAccess,
      command: PendingAfterSaleReview,
      recovering: boolean,
    ): Promise<AfterSale | null> {
      const requestRevision = ++commandRevision;
      submitting.value = true;
      retryAllowed.value = false;
      reviewPhase.value = "processing";
      reviewMessage.value =
        `售后 ${command.referenceNo} 的${decisionLabel(command)}正在等待 Trade 确认。`;
      try {
        const value = validateAfterSale(
          await access.api.reviewAfterSale(command.referenceNo, {
            approved: command.approved,
            reason: command.reason,
          }),
          command.referenceNo,
        );
        requireCurrent(access);
        if (requestRevision !== commandRevision) {
          return null;
        }
        if (!directResultMatches(command, value)) {
          throw new AfterSaleContractError(
            "Trade 已响应，但返回状态、审核原因或批准时间与原审核载荷不一致。",
          );
        }
        upsertAfterSale(value);
        settleAccepted(
          access,
          `Trade 已确认售后 ${value.afterSaleNo} 的${decisionLabel(command)}，当前状态为 ${value.status}。`,
        );
        return value;
      } catch (cause) {
        if (
          !accessIsCurrent(access)
          || requestRevision !== commandRevision
        ) {
          return null;
        }
        if (resultMayBeUnknown(cause)) {
          reviewPhase.value = "unknown";
          reviewMessage.value =
            `${errorMessage(cause, "售后审核响应未能确认。")} `
            + "页面没有显示成功；原决定与原因已保留，必须先读取 Trade 权威事实。";
          return null;
        }
        if (recovering) {
          reviewPhase.value = "unknown";
          reviewMessage.value =
            `${errorMessage(cause, "原审核载荷重试被明确拒绝。")} `
            + "该拒绝只属于本次重试，不能反推第一次响应的结果；请再次读取 Trade 权威事实。";
          return null;
        }
        settleRejected(
          access,
          errorMessage(cause, "Trade 已明确拒绝当前售后审核。"),
        );
        return null;
      } finally {
        if (
          accessIsCurrent(access)
          && requestRevision === commandRevision
        ) {
          submitting.value = false;
        }
      }
    }

    function runPending(
      access: ActiveAccess,
      command: PendingAfterSaleReview,
    ): Promise<AfterSale | null> {
      if (activeCommandPromise) {
        return activeCommandPromise;
      }
      beginPending(access, command);
      const request = executePending(access, command, false);
      activeCommandPromise = request;
      const clear = () => {
        if (activeCommandPromise === request) {
          activeCommandPromise = null;
        }
      };
      void request.then(clear, clear);
      return request;
    }

    function review(
      referenceNo: string,
      context: AdminAfterSaleAccessContext,
    ): Promise<AfterSale | null> {
      const access = requireAccess(
        context,
        "当前会话无权审核售后。",
      );
      if (!access) {
        return Promise.resolve(null);
      }
      if (commandBlocked.value) {
        reviewPhase.value = "unknown";
        reviewMessage.value =
          "上一条售后审核尚未确认，不能提交第二条审核。";
        return Promise.resolve(null);
      }
      const form = reviewForm(referenceNo);
      const reason = form.reason.trim();
      if (!reason) {
        reviewPhase.value = "rejected";
        reviewMessage.value = "审核必须记录明确原因。";
        return Promise.resolve(null);
      }
      return runPending(access, {
        referenceNo,
        approved: form.approved,
        reason,
        createdAt: new Date().toISOString(),
      });
    }

    async function readPendingAuthority(
      context: AdminAfterSaleAccessContext,
    ): Promise<AfterSale | null> {
      const access = requireAccess(
        context,
        "当前会话无权读取 Trade 售后事实。",
      );
      const command = pendingReview.value;
      if (!access || !command) {
        if (!command) {
          reviewPhase.value = "rejected";
          reviewMessage.value = "当前没有待核对的结果未知审核。";
        }
        return null;
      }
      const requestRevision = ++commandRevision;
      submitting.value = true;
      retryAllowed.value = false;
      try {
        const value = validateAfterSale(
          await access.api.adminAfterSale(command.referenceNo),
          command.referenceNo,
        );
        requireCurrent(access);
        if (requestRevision !== commandRevision) {
          return null;
        }
        upsertAfterSale(value);
        if (authorityMatches(command, value)) {
          settleAccepted(
            access,
            `Trade 权威事实已确认相同审核决定与原因，售后 ${value.afterSaleNo} 当前状态为 ${value.status}。接口不公开审核命令 ID，因此只确认业务结果，不伪造命令身份。`,
          );
          return value;
        }
        if (value.status === "APPLIED") {
          retryAllowed.value = true;
          reviewPhase.value = "unknown";
          reviewMessage.value =
            "Trade 当前仍为 APPLIED，原审核效果尚不可见。现在只允许使用已冻结的原决定与原因重试一次，不能编辑载荷或提交其他审核。";
          return value;
        }
        settleRejected(
          access,
          `Trade 当前状态为 ${value.status}，审核原因也不能证明原载荷已生效；该售后已由其他事实推进，原审核不再允许重试。`,
        );
        return value;
      } catch (cause) {
        if (accessIsCurrent(access) && requestRevision === commandRevision) {
          reviewPhase.value = "unknown";
          reviewMessage.value =
            `${errorMessage(cause, "Trade 售后权威事实暂时无法读取。")} `
            + "原审核继续保持结果未知，且不能重试或开始第二条审核。";
        }
        return null;
      } finally {
        if (
          accessIsCurrent(access)
          && requestRevision === commandRevision
        ) {
          submitting.value = false;
        }
      }
    }

    function retryPending(
      context: AdminAfterSaleAccessContext,
    ): Promise<AfterSale | null> {
      const access = requireAccess(
        context,
        "当前会话无权重试售后审核。",
      );
      const command = pendingReview.value;
      if (!access || !command) {
        if (!command) {
          reviewPhase.value = "rejected";
          reviewMessage.value = "当前没有可重试的结果未知审核。";
        }
        return Promise.resolve(null);
      }
      if (!retryAllowed.value) {
        reviewPhase.value = "unknown";
        reviewMessage.value =
          "必须先读取 Trade 权威事实，并确认售后仍为 APPLIED，才能使用原审核载荷重试。";
        return Promise.resolve(null);
      }
      if (activeCommandPromise) {
        return activeCommandPromise;
      }
      const request = executePending(access, command, true);
      activeCommandPromise = request;
      const clear = () => {
        if (activeCommandPromise === request) {
          activeCommandPromise = null;
        }
      };
      void request.then(clear, clear);
      return request;
    }

    function resetReviewNotice() {
      if (pendingReview.value) {
        return;
      }
      reviewPhase.value = "idle";
      reviewMessage.value = null;
    }

    return {
      afterSales,
      status,
      loading,
      loadError,
      refreshedAt,
      reviewPhase,
      reviewMessage,
      pendingReview,
      pendingReferenceNo,
      pendingDecision,
      submitting,
      commandBlocked,
      canRetryPending,
      synchronizeAccess,
      reviewForm,
      loadFacts,
      review,
      readPendingAuthority,
      retryPending,
      resetReviewNotice,
    };
  },
);
