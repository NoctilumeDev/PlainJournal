import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createGovernanceApi,
  createPaymentApi,
  secureRandomUUID,
  type BusinessId,
  type GovernanceApi,
  type PaymentApi,
  type PaymentExceptionRefundAudit,
  type ReconciliationDomain,
  type ReconciliationIssue,
  type ReconciliationStatus,
  type Refund,
  type RefundDispatchRetryAudit,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const REFUND_RETRY_STORAGE_PREFIX =
  "plain-journal:admin-governance:refund-retry:v1:";
const PAYMENT_EXCEPTION_STORAGE_PREFIX =
  "plain-journal:admin-governance:payment-exception-refund:v1:";

export const GOVERNANCE_DOMAINS: ReconciliationDomain[] = [
  "trade",
  "payment",
  "inventory",
  "fulfillment",
];

export type CompensationPhase =
  | "idle"
  | "invalid"
  | "unknown"
  | "accepted"
  | "rejected";

export interface GovernanceAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveGovernanceAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  governanceApi: GovernanceApi;
  paymentApi: PaymentApi;
}

interface PendingCompensationCommand {
  referenceNo: string;
  commandId: string;
  reason: string;
  createdAt: string;
}

interface CompensationTrack<TAudit> {
  referenceNo: string;
  commandId: string;
  reason: string;
  phase: CompensationPhase;
  message: string | null;
  result: Refund | null;
  audits: TAudit[];
  auditError: string | null;
  submitting: boolean;
  loadingAudits: boolean;
}

export class GovernanceAccessChangedError extends Error {
  constructor() {
    super("管理员账户或会话已切换，旧的治理请求结果不会写入当前页面。");
    this.name = "GovernanceAccessChangedError";
  }
}

export class GovernanceContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GovernanceContractError";
  }
}

function isActiveContext(context: GovernanceAccessContext): context is {
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

function createApis(accessToken: string) {
  const client = createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  });
  return {
    governanceApi: createGovernanceApi(client),
    paymentApi: createPaymentApi(client),
  };
}

function newCommandId(prefix: string): string {
  return `${prefix}:${secureRandomUUID()}`;
}

function newTrack<TAudit>(prefix: string): CompensationTrack<TAudit> {
  return {
    referenceNo: "",
    commandId: newCommandId(prefix),
    reason: "",
    phase: "idle",
    message: null,
    result: null,
    audits: [],
    auditError: null,
    submitting: false,
    loadingAudits: false,
  };
}

function storageKey(prefix: string, operatorId: BusinessId): string {
  return `${prefix}${operatorId}`;
}

function parsePending(
  raw: string | null,
  commandPrefix: string,
): PendingCompensationCommand | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("referenceNo" in value)
      || !("commandId" in value)
      || !("reason" in value)
      || !("createdAt" in value)
      || typeof value.referenceNo !== "string"
      || value.referenceNo.length === 0
      || typeof value.commandId !== "string"
      || !value.commandId.startsWith(`${commandPrefix}:`)
      || typeof value.reason !== "string"
      || value.reason.length === 0
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingCompensationCommand;
  } catch {
    return null;
  }
}

function loadPending(
  prefix: string,
  operatorId: BusinessId,
  commandPrefix: string,
): PendingCompensationCommand | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(
    localStorage.getItem(storageKey(prefix, operatorId)),
    commandPrefix,
  );
}

function savePending(
  prefix: string,
  operatorId: BusinessId,
  value: PendingCompensationCommand | null,
) {
  if (typeof localStorage === "undefined") {
    return;
  }
  const key = storageKey(prefix, operatorId);
  if (value) {
    localStorage.setItem(key, JSON.stringify(value));
  } else {
    localStorage.removeItem(key);
  }
}

function restoreTrack<TAudit>(
  track: CompensationTrack<TAudit>,
  commandPrefix: string,
  pending: PendingCompensationCommand | null,
) {
  Object.assign(track, newTrack<TAudit>(commandPrefix));
  if (!pending) {
    return;
  }
  track.referenceNo = pending.referenceNo;
  track.commandId = pending.commandId;
  track.reason = pending.reason;
  track.phase = "unknown";
  track.message =
    "发现一条尚未确认的治理命令。原业务编号、命令 ID 与原因已恢复；请先读取权威审计。";
}

function resultMayBeUnknown(cause: unknown): boolean {
  if (cause instanceof GovernanceContractError) {
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

function validateRefundRetryResult(
  value: Refund,
  pending: PendingCompensationCommand,
) {
  if (value.refundNo !== pending.referenceNo) {
    throw new GovernanceContractError(
      "Payment 已响应，但退款事实与本次补偿命令不一致。",
    );
  }
}

function validatePaymentExceptionResult(
  value: Refund,
  pending: PendingCompensationCommand,
) {
  if (value.paymentNo !== pending.referenceNo) {
    throw new GovernanceContractError(
      "Payment 已响应，但异常支付退款事实与本次命令不一致。",
    );
  }
}

function matchingRefundAudit(
  audits: RefundDispatchRetryAudit[],
  pending: PendingCompensationCommand,
): RefundDispatchRetryAudit | null {
  return audits.find((audit) =>
    audit.commandId === pending.commandId
    && audit.refundNo === pending.referenceNo
    && audit.reason === pending.reason) ?? null;
}

function matchingExceptionAudit(
  audits: PaymentExceptionRefundAudit[],
  pending: PendingCompensationCommand,
): PaymentExceptionRefundAudit | null {
  return audits.find((audit) =>
    audit.commandId === pending.commandId
    && audit.paymentNo === pending.referenceNo
    && audit.reason === pending.reason) ?? null;
}

export const useGovernanceStore = defineStore("admin-governance", () => {
  const reconciliationStatus = ref<ReconciliationStatus>("OPEN");
  const issues = reactive<Record<ReconciliationDomain, ReconciliationIssue[]>>({
    trade: [],
    payment: [],
    inventory: [],
    fulfillment: [],
  });
  const loadingIssues = ref(false);
  const issuesError = ref<string | null>(null);
  const refundRetry = reactive(
    newTrack<RefundDispatchRetryAudit>("refund-retry"),
  );
  const paymentExceptionRefund = reactive(
    newTrack<PaymentExceptionRefundAudit>("payment-exception-refund"),
  );
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let issuesRevision = 0;
  let refundRevision = 0;
  let exceptionRevision = 0;
  let pendingRefundRetry: PendingCompensationCommand | null = null;
  let pendingPaymentException: PendingCompensationCommand | null = null;
  let activeRefundPromise: Promise<Refund | null> | null = null;
  let activeExceptionPromise: Promise<Refund | null> | null = null;

  const totalIssues = computed(() =>
    GOVERNANCE_DOMAINS.reduce(
      (total, domain) => total + issues[domain].length,
      0,
    ));

  function synchronizeAccess(
    context: GovernanceAccessContext,
  ): ActiveGovernanceAccess | null {
    const nextOperatorId = isActiveContext(context) ? context.operatorId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const operatorChanged = activeOperatorId.value !== nextOperatorId;
    const accessChanged = operatorChanged || activeAccessToken !== nextAccessToken;

    if (accessChanged) {
      activeOperatorId.value = nextOperatorId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      issuesRevision += 1;
      refundRevision += 1;
      exceptionRevision += 1;
      loadingIssues.value = false;
      refundRetry.submitting = false;
      refundRetry.loadingAudits = false;
      paymentExceptionRefund.submitting = false;
      paymentExceptionRefund.loadingAudits = false;
      activeRefundPromise = null;
      activeExceptionPromise = null;

      if (operatorChanged) {
        for (const domain of GOVERNANCE_DOMAINS) {
          issues[domain] = [];
        }
        issuesError.value = null;
        pendingRefundRetry = nextOperatorId
          ? loadPending(
              REFUND_RETRY_STORAGE_PREFIX,
              nextOperatorId,
              "refund-retry",
            )
          : null;
        pendingPaymentException = nextOperatorId
          ? loadPending(
              PAYMENT_EXCEPTION_STORAGE_PREFIX,
              nextOperatorId,
              "payment-exception-refund",
            )
          : null;
        restoreTrack(refundRetry, "refund-retry", pendingRefundRetry);
        restoreTrack(
          paymentExceptionRefund,
          "payment-exception-refund",
          pendingPaymentException,
        );
      } else {
        if (pendingRefundRetry) {
          refundRetry.phase = "unknown";
          refundRetry.message =
            "管理员会话凭据已更新，退款补偿结果继续保持待确认。";
        }
        if (pendingPaymentException) {
          paymentExceptionRefund.phase = "unknown";
          paymentExceptionRefund.message =
            "管理员会话凭据已更新，异常支付退款结果继续保持待确认。";
        }
      }
    }

    if (!isActiveContext(context)) {
      return null;
    }
    const apis = createApis(context.accessToken);
    return {
      operatorId: context.operatorId,
      accessToken: context.accessToken,
      revision: accessRevision,
      ...apis,
    };
  }

  function accessIsCurrent(access: ActiveGovernanceAccess): boolean {
    return access.revision === accessRevision
      && access.operatorId === activeOperatorId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveGovernanceAccess) {
    if (!accessIsCurrent(access)) {
      throw new GovernanceAccessChangedError();
    }
  }

  async function loadIssues(
    context: GovernanceAccessContext,
  ): Promise<Record<ReconciliationDomain, ReconciliationIssue[]>> {
    const access = synchronizeAccess(context);
    if (!access) {
      issuesError.value = "当前会话无权读取所有者域对账事实。";
      return issues;
    }
    const requestRevision = ++issuesRevision;
    loadingIssues.value = true;
    issuesError.value = null;
    try {
      const values = await Promise.all(GOVERNANCE_DOMAINS.map((domain) =>
        access.governanceApi.reconciliationIssues(
          domain,
          reconciliationStatus.value,
          50,
        )));
      requireCurrent(access);
      if (requestRevision !== issuesRevision) {
        return issues;
      }
      GOVERNANCE_DOMAINS.forEach((domain, index) => {
        issues[domain] = values[index] ?? [];
      });
      return issues;
    } catch (cause) {
      requireCurrent(access);
      if (requestRevision === issuesRevision) {
        issuesError.value = errorMessage(
          cause,
          "四域对账事实暂时无法读取。",
        );
      }
      return issues;
    } finally {
      if (accessIsCurrent(access) && requestRevision === issuesRevision) {
        loadingIssues.value = false;
      }
    }
  }

  function prepareRefundRetry(
    access: ActiveGovernanceAccess,
  ): PendingCompensationCommand | null {
    if (pendingRefundRetry) {
      return pendingRefundRetry;
    }
    const referenceNo = refundRetry.referenceNo.trim();
    const reason = refundRetry.reason.trim();
    if (!referenceNo || !reason) {
      refundRetry.phase = "invalid";
      refundRetry.message = "退款号和补偿原因不能为空。";
      return null;
    }
    const pending: PendingCompensationCommand = {
      referenceNo,
      commandId: refundRetry.commandId,
      reason,
      createdAt: new Date().toISOString(),
    };
    pendingRefundRetry = pending;
    refundRetry.referenceNo = referenceNo;
    refundRetry.reason = reason;
    savePending(REFUND_RETRY_STORAGE_PREFIX, access.operatorId, pending);
    return pending;
  }

  function submitRefundRetry(
    context: GovernanceAccessContext,
  ): Promise<Refund | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      refundRetry.phase = "rejected";
      refundRetry.message = "当前会话无权提交 Payment 退款补偿命令。";
      return Promise.resolve(null);
    }
    if (activeRefundPromise) {
      return activeRefundPromise;
    }
    const request = submitRefundRetryForAccess(access);
    activeRefundPromise = request;
    const clear = () => {
      if (activeRefundPromise === request) {
        activeRefundPromise = null;
      }
    };
    void request.then(clear, clear);
    return request;
  }

  async function submitRefundRetryForAccess(
    access: ActiveGovernanceAccess,
  ): Promise<Refund | null> {
    const pending = prepareRefundRetry(access);
    if (!pending) {
      return null;
    }
    const requestRevision = ++refundRevision;
    refundRetry.submitting = true;
    refundRetry.auditError = null;
    refundRetry.message = null;
    try {
      const value = await access.paymentApi.retryRefundDispatch(
        pending.referenceNo,
        pending.commandId,
        pending.reason,
      );
      requireCurrent(access);
      if (requestRevision !== refundRevision) {
        return null;
      }
      validateRefundRetryResult(value, pending);
      refundRetry.result = value;
      refundRetry.phase = "accepted";
      refundRetry.message =
        `Payment 已接受命令；退款当前为 ${value.status} / ${value.requestStatus}。`;
      pendingRefundRetry = null;
      savePending(REFUND_RETRY_STORAGE_PREFIX, access.operatorId, null);
      return value;
    } catch (cause) {
      requireCurrent(access);
      if (requestRevision !== refundRevision) {
        return null;
      }
      refundRetry.result = null;
      if (resultMayBeUnknown(cause)) {
        refundRetry.phase = "unknown";
        refundRetry.message =
          `${errorMessage(cause, "退款补偿响应未能确认。")} `
          + "原命令 ID、退款号和原因已保留；请先读取权威审计，必要时再用原 ID 重试。";
        return null;
      }
      refundRetry.phase = "rejected";
      refundRetry.message = errorMessage(
        cause,
        "Payment 已明确拒绝退款补偿命令。",
      );
      pendingRefundRetry = null;
      savePending(REFUND_RETRY_STORAGE_PREFIX, access.operatorId, null);
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === refundRevision) {
        refundRetry.submitting = false;
      }
    }
  }

  async function loadRefundRetryAudits(
    context: GovernanceAccessContext,
  ): Promise<RefundDispatchRetryAudit[]> {
    const access = synchronizeAccess(context);
    if (!access) {
      refundRetry.auditError = "当前会话无权读取退款补偿审计。";
      return refundRetry.audits;
    }
    const referenceNo =
      pendingRefundRetry?.referenceNo ?? refundRetry.referenceNo.trim();
    if (!referenceNo) {
      refundRetry.auditError = "请先填写退款号。";
      return refundRetry.audits;
    }
    const requestRevision = ++refundRevision;
    refundRetry.loadingAudits = true;
    refundRetry.auditError = null;
    try {
      const values = await access.paymentApi.refundRetryAudits(referenceNo);
      requireCurrent(access);
      if (requestRevision !== refundRevision) {
        return refundRetry.audits;
      }
      refundRetry.audits = values;
      if (pendingRefundRetry) {
        const audit = matchingRefundAudit(values, pendingRefundRetry);
        if (!audit) {
          refundRetry.phase = "unknown";
          refundRetry.message =
            "权威审计尚未记录当前命令，结果继续保持未知；不能生成新命令 ID。";
        } else if (audit.outcome === "ACCEPTED") {
          refundRetry.phase = "accepted";
          refundRetry.message =
            `权威审计已确认命令 ACCEPTED：`
            + `${audit.beforeRequestStatus} → ${audit.afterRequestStatus}。`;
          pendingRefundRetry = null;
          savePending(REFUND_RETRY_STORAGE_PREFIX, access.operatorId, null);
        } else if (audit.outcome === "REJECTED") {
          refundRetry.phase = "rejected";
          refundRetry.message =
            `权威审计已确认命令 REJECTED：${audit.errorCode ?? "未提供错误码"}。`;
          pendingRefundRetry = null;
          savePending(REFUND_RETRY_STORAGE_PREFIX, access.operatorId, null);
        } else {
          refundRetry.phase = "unknown";
          refundRetry.message =
            `审计返回未识别结果 ${audit.outcome}，页面不会猜测命令已成功。`;
        }
      }
      return values;
    } catch (cause) {
      requireCurrent(access);
      if (requestRevision === refundRevision) {
        refundRetry.auditError = errorMessage(
          cause,
          "退款补偿审计暂时无法读取。",
        );
        if (pendingRefundRetry) {
          refundRetry.phase = "unknown";
          refundRetry.message =
            "权威审计当前不可用，原命令继续保持结果未知。";
        }
      }
      return refundRetry.audits;
    } finally {
      if (accessIsCurrent(access) && requestRevision === refundRevision) {
        refundRetry.loadingAudits = false;
      }
    }
  }

  function preparePaymentException(
    access: ActiveGovernanceAccess,
  ): PendingCompensationCommand | null {
    if (pendingPaymentException) {
      return pendingPaymentException;
    }
    const referenceNo = paymentExceptionRefund.referenceNo.trim();
    const reason = paymentExceptionRefund.reason.trim();
    if (!referenceNo || !reason) {
      paymentExceptionRefund.phase = "invalid";
      paymentExceptionRefund.message = "支付号和授权退款原因不能为空。";
      return null;
    }
    const pending: PendingCompensationCommand = {
      referenceNo,
      commandId: paymentExceptionRefund.commandId,
      reason,
      createdAt: new Date().toISOString(),
    };
    pendingPaymentException = pending;
    paymentExceptionRefund.referenceNo = referenceNo;
    paymentExceptionRefund.reason = reason;
    savePending(PAYMENT_EXCEPTION_STORAGE_PREFIX, access.operatorId, pending);
    return pending;
  }

  function submitPaymentExceptionRefund(
    context: GovernanceAccessContext,
  ): Promise<Refund | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      paymentExceptionRefund.phase = "rejected";
      paymentExceptionRefund.message =
        "当前会话无权提交异常支付退款命令。";
      return Promise.resolve(null);
    }
    if (activeExceptionPromise) {
      return activeExceptionPromise;
    }
    const request = submitPaymentExceptionRefundForAccess(access);
    activeExceptionPromise = request;
    const clear = () => {
      if (activeExceptionPromise === request) {
        activeExceptionPromise = null;
      }
    };
    void request.then(clear, clear);
    return request;
  }

  async function submitPaymentExceptionRefundForAccess(
    access: ActiveGovernanceAccess,
  ): Promise<Refund | null> {
    const pending = preparePaymentException(access);
    if (!pending) {
      return null;
    }
    const requestRevision = ++exceptionRevision;
    paymentExceptionRefund.submitting = true;
    paymentExceptionRefund.auditError = null;
    paymentExceptionRefund.message = null;
    try {
      const value = await access.paymentApi.createPaymentExceptionRefund(
        pending.referenceNo,
        pending.commandId,
        pending.reason,
      );
      requireCurrent(access);
      if (requestRevision !== exceptionRevision) {
        return null;
      }
      validatePaymentExceptionResult(value, pending);
      paymentExceptionRefund.result = value;
      paymentExceptionRefund.phase = "accepted";
      paymentExceptionRefund.message =
        `Payment 已创建退款 ${value.refundNo}；`
        + `当前为 ${value.status} / ${value.requestStatus}，并未伪造渠道成功。`;
      pendingPaymentException = null;
      savePending(PAYMENT_EXCEPTION_STORAGE_PREFIX, access.operatorId, null);
      return value;
    } catch (cause) {
      requireCurrent(access);
      if (requestRevision !== exceptionRevision) {
        return null;
      }
      paymentExceptionRefund.result = null;
      if (resultMayBeUnknown(cause)) {
        paymentExceptionRefund.phase = "unknown";
        paymentExceptionRefund.message =
          `${errorMessage(cause, "异常支付退款响应未能确认。")} `
          + "原命令 ID、支付号和原因已保留；请先读取权威审计，必要时再用原 ID 重试。";
        return null;
      }
      paymentExceptionRefund.phase = "rejected";
      paymentExceptionRefund.message = errorMessage(
        cause,
        "Payment 已明确拒绝异常支付退款命令。",
      );
      pendingPaymentException = null;
      savePending(PAYMENT_EXCEPTION_STORAGE_PREFIX, access.operatorId, null);
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === exceptionRevision) {
        paymentExceptionRefund.submitting = false;
      }
    }
  }

  async function loadPaymentExceptionAudits(
    context: GovernanceAccessContext,
  ): Promise<PaymentExceptionRefundAudit[]> {
    const access = synchronizeAccess(context);
    if (!access) {
      paymentExceptionRefund.auditError =
        "当前会话无权读取异常支付退款审计。";
      return paymentExceptionRefund.audits;
    }
    const referenceNo =
      pendingPaymentException?.referenceNo
      ?? paymentExceptionRefund.referenceNo.trim();
    if (!referenceNo) {
      paymentExceptionRefund.auditError = "请先填写支付号。";
      return paymentExceptionRefund.audits;
    }
    const requestRevision = ++exceptionRevision;
    paymentExceptionRefund.loadingAudits = true;
    paymentExceptionRefund.auditError = null;
    try {
      const values = await access.paymentApi
        .paymentExceptionRefundAudits(referenceNo);
      requireCurrent(access);
      if (requestRevision !== exceptionRevision) {
        return paymentExceptionRefund.audits;
      }
      paymentExceptionRefund.audits = values;
      if (pendingPaymentException) {
        const audit = matchingExceptionAudit(values, pendingPaymentException);
        if (!audit) {
          paymentExceptionRefund.phase = "unknown";
          paymentExceptionRefund.message =
            "权威审计尚未记录当前命令，结果继续保持未知；不能生成新命令 ID。";
        } else if (audit.outcome === "ACCEPTED") {
          paymentExceptionRefund.phase = "accepted";
          paymentExceptionRefund.message =
            `权威审计已确认命令 ACCEPTED，退款号为 `
            + `${audit.refundNo ?? "审计未返回退款号"}。`;
          pendingPaymentException = null;
          savePending(
            PAYMENT_EXCEPTION_STORAGE_PREFIX,
            access.operatorId,
            null,
          );
        } else if (audit.outcome === "REJECTED") {
          paymentExceptionRefund.phase = "rejected";
          paymentExceptionRefund.message =
            `权威审计已确认命令 REJECTED：`
            + `${audit.errorCode ?? "未提供错误码"}。`;
          pendingPaymentException = null;
          savePending(
            PAYMENT_EXCEPTION_STORAGE_PREFIX,
            access.operatorId,
            null,
          );
        } else {
          paymentExceptionRefund.phase = "unknown";
          paymentExceptionRefund.message =
            `审计返回未识别结果 ${audit.outcome}，页面不会猜测命令已成功。`;
        }
      }
      return values;
    } catch (cause) {
      requireCurrent(access);
      if (requestRevision === exceptionRevision) {
        paymentExceptionRefund.auditError = errorMessage(
          cause,
          "异常支付退款审计暂时无法读取。",
        );
        if (pendingPaymentException) {
          paymentExceptionRefund.phase = "unknown";
          paymentExceptionRefund.message =
            "权威审计当前不可用，原命令继续保持结果未知。";
        }
      }
      return paymentExceptionRefund.audits;
    } finally {
      if (accessIsCurrent(access) && requestRevision === exceptionRevision) {
        paymentExceptionRefund.loadingAudits = false;
      }
    }
  }

  function resetRefundRetry(): boolean {
    if (pendingRefundRetry || refundRetry.phase === "unknown") {
      refundRetry.message =
        "当前命令尚未收敛，不能生成新命令 ID。请先读取权威审计。";
      return false;
    }
    restoreTrack(refundRetry, "refund-retry", null);
    return true;
  }

  function resetPaymentExceptionRefund(): boolean {
    if (
      pendingPaymentException
      || paymentExceptionRefund.phase === "unknown"
    ) {
      paymentExceptionRefund.message =
        "当前命令尚未收敛，不能生成新命令 ID。请先读取权威审计。";
      return false;
    }
    restoreTrack(
      paymentExceptionRefund,
      "payment-exception-refund",
      null,
    );
    return true;
  }

  return {
    reconciliationStatus,
    issues,
    loadingIssues,
    issuesError,
    totalIssues,
    refundRetry,
    paymentExceptionRefund,
    activeOperatorId,
    synchronizeAccess,
    loadIssues,
    submitRefundRetry,
    loadRefundRetryAudits,
    submitPaymentExceptionRefund,
    loadPaymentExceptionAudits,
    resetRefundRetry,
    resetPaymentExceptionRefund,
  };
});
