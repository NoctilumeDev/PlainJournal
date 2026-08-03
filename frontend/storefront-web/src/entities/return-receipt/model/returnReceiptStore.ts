import { ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createFulfillmentApi,
  type BusinessId,
  type FulfillmentApi,
  type ReturnReceipt,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface ReturnReceiptAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveReturnAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

class ReturnReceiptResponseMismatchError extends Error {
  constructor() {
    super("Fulfillment 已响应，但返回的退货事实与本次账户或请求不一致。");
    this.name = "ReturnReceiptResponseMismatchError";
  }
}

function isActiveContext(context: ReturnReceiptAccessContext): context is {
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

function fulfillmentApi(accessToken: string): FulfillmentApi {
  return createFulfillmentApi(createApiClient({
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
  if (cause instanceof ApiError && cause.status === 404) {
    return "退货单不存在，或不属于当前账户。";
  }
  return cause instanceof Error ? cause.message : fallback;
}

export const useReturnReceiptsStore = defineStore("customer-return-receipts", () => {
  const returnReceipts = ref<ReturnReceipt[]>([]);
  const loading = ref(false);
  const loadingNo = ref<string | null>(null);
  const submittingNo = ref<string | null>(null);
  const error = ref<string | null>(null);
  const submissionUnknown = ref(false);
  const submissionError = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let listRevision = 0;
  let detailRevision = 0;
  let submissionRevision = 0;
  let activeSubmissionPromise: Promise<ReturnReceipt | null> | null = null;
  let activeSubmissionFingerprint: string | null = null;
  let activeSubmissionAccessRevision = -1;

  function synchronizeAccess(context: ReturnReceiptAccessContext): ActiveReturnAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;
    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      listRevision += 1;
      detailRevision += 1;
      submissionRevision += 1;
      activeSubmissionPromise = null;
      activeSubmissionFingerprint = null;
      activeSubmissionAccessRevision = -1;
      loading.value = false;
      loadingNo.value = null;
      submittingNo.value = null;
      error.value = null;
      submissionUnknown.value = false;
      submissionError.value = null;
      if (ownerChanged) {
        returnReceipts.value = [];
      }
    }
    if (!isActiveContext(context)) {
      returnReceipts.value = [];
      return null;
    }
    return { ownerId: context.ownerId, accessToken: context.accessToken, revision: accessRevision };
  }

  function accessIsCurrent(access: ActiveReturnAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function find(returnReceiptNo: string): ReturnReceipt | null {
    return returnReceipts.value.find((value) => value.returnReceiptNo === returnReceiptNo) ?? null;
  }

  function forAfterSale(afterSaleNo: string): ReturnReceipt | null {
    return returnReceipts.value.find((value) => value.afterSaleNo === afterSaleNo) ?? null;
  }

  function verifyFact(
    access: ActiveReturnAccess,
    value: ReturnReceipt,
    expected?: { returnReceiptNo?: string; afterSaleNo?: string },
  ) {
    if (
      value.userId !== access.ownerId
      || (expected?.returnReceiptNo && value.returnReceiptNo !== expected.returnReceiptNo)
      || (expected?.afterSaleNo && value.afterSaleNo !== expected.afterSaleNo)
    ) {
      throw new ReturnReceiptResponseMismatchError();
    }
  }

  function upsert(value: ReturnReceipt) {
    const index = returnReceipts.value.findIndex((candidate) =>
      candidate.returnReceiptNo === value.returnReceiptNo);
    if (index >= 0) {
      returnReceipts.value[index] = value;
    } else {
      returnReceipts.value.unshift(value);
    }
  }

  async function load(context: ReturnReceiptAccessContext): Promise<ReturnReceipt[]> {
    const access = synchronizeAccess(context);
    if (!access) {
      return [];
    }
    const requestRevision = ++listRevision;
    loading.value = true;
    error.value = null;
    try {
      const values = await fulfillmentApi(access.accessToken).returns();
      if (!accessIsCurrent(access) || requestRevision !== listRevision) {
        return returnReceipts.value;
      }
      for (const value of values) {
        verifyFact(access, value);
      }
      returnReceipts.value = values;
      return values;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        error.value = readError(cause, "退货列表暂时无法读取。");
      }
      return returnReceipts.value;
    } finally {
      if (accessIsCurrent(access) && requestRevision === listRevision) {
        loading.value = false;
      }
    }
  }

  async function loadOneForAccess(
    access: ActiveReturnAccess,
    returnReceiptNo: string,
  ): Promise<ReturnReceipt | null> {
    const requestRevision = ++detailRevision;
    loadingNo.value = returnReceiptNo;
    error.value = null;
    try {
      const value = await fulfillmentApi(access.accessToken).returnReceipt(returnReceiptNo);
      if (!accessIsCurrent(access) || requestRevision !== detailRevision) {
        return null;
      }
      verifyFact(access, value, { returnReceiptNo });
      upsert(value);
      return value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === detailRevision) {
        error.value = readError(cause, "退货事实暂时无法读取。");
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === detailRevision) {
        loadingNo.value = null;
      }
    }
  }

  async function loadOne(
    context: ReturnReceiptAccessContext,
    returnReceiptNo: string,
  ): Promise<ReturnReceipt | null> {
    const access = synchronizeAccess(context);
    return access ? loadOneForAccess(access, returnReceiptNo) : null;
  }

  function submitShipment(
    context: ReturnReceiptAccessContext,
    returnReceiptNo: string,
    carrier: string,
    trackingNo: string,
  ): Promise<ReturnReceipt | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      submissionError.value = "当前会话没有可用于提交寄回信息的顾客事实。";
      return Promise.resolve(null);
    }
    const normalizedCarrier = carrier.trim().toUpperCase();
    const normalizedTrackingNo = trackingNo.trim();
    const fingerprint = `${returnReceiptNo}\u0000${normalizedCarrier}\u0000${normalizedTrackingNo}`;
    if (activeSubmissionPromise && activeSubmissionAccessRevision === access.revision) {
      if (activeSubmissionFingerprint === fingerprint) {
        return activeSubmissionPromise;
      }
      submissionUnknown.value = true;
      submissionError.value = "同一退货单已有寄回请求正在确认，不会并发提交另一组运单事实。";
      return Promise.resolve(null);
    }
    const request = submitShipmentForAccess(
      access,
      returnReceiptNo,
      normalizedCarrier,
      normalizedTrackingNo,
    );
    activeSubmissionPromise = request;
    activeSubmissionFingerprint = fingerprint;
    activeSubmissionAccessRevision = access.revision;
    const cleanup = () => {
      if (activeSubmissionPromise === request) {
        activeSubmissionPromise = null;
        activeSubmissionFingerprint = null;
        activeSubmissionAccessRevision = -1;
      }
    };
    void request.then(cleanup, cleanup);
    return request;
  }

  async function submitShipmentForAccess(
    access: ActiveReturnAccess,
    returnReceiptNo: string,
    carrier: string,
    trackingNo: string,
  ): Promise<ReturnReceipt | null> {
    if (!carrier || !trackingNo) {
      submissionError.value = "承运商和运单号不能为空。";
      return null;
    }
    submissionError.value = null;
    let known = find(returnReceiptNo);
    if (!known) {
      known = await loadOneForAccess(access, returnReceiptNo);
    }
    if (!accessIsCurrent(access) || !known) {
      return null;
    }
    if (known.status !== "WAIT_SHIPMENT") {
      if (known.carrier === carrier && known.trackingNo === trackingNo) {
        submissionUnknown.value = false;
        return known;
      }
      submissionUnknown.value = false;
      submissionError.value = "退货单已经记录其他运单事实，页面不会覆盖或重复提交。";
      return known;
    }

    const requestRevision = ++submissionRevision;
    submittingNo.value = returnReceiptNo;
    try {
      const value = await fulfillmentApi(access.accessToken).submitReturnShipment(
        returnReceiptNo,
        carrier,
        trackingNo,
      );
      if (!accessIsCurrent(access) || requestRevision !== submissionRevision) {
        return null;
      }
      verifyFact(access, value, { returnReceiptNo, afterSaleNo: known.afterSaleNo });
      upsert(value);
      const confirmed = value.status !== "WAIT_SHIPMENT"
        && value.carrier === carrier
        && value.trackingNo === trackingNo;
      submissionUnknown.value = !confirmed;
      submissionError.value = confirmed
        ? null
        : "Fulfillment 已响应，但尚未返回与本次运单一致的寄回事实。";
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== submissionRevision) {
        return null;
      }
      if (isUncertain(cause)) {
        const recovered = await loadOneForAccess(access, returnReceiptNo);
        if (
          recovered
          && recovered.status !== "WAIT_SHIPMENT"
          && recovered.carrier === carrier
          && recovered.trackingNo === trackingNo
        ) {
          submissionUnknown.value = false;
          submissionError.value = null;
          return recovered;
        }
        submissionUnknown.value = true;
        submissionError.value = "寄回信息提交结果尚未确认。请先查询退货事实，不要更换运单号重复提交。";
        return recovered;
      }
      submissionUnknown.value = false;
      submissionError.value = cause instanceof Error ? cause.message : "寄回信息提交未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === submissionRevision) {
        submittingNo.value = null;
      }
    }
  }

  return {
    returnReceipts,
    loading,
    loadingNo,
    submittingNo,
    error,
    submissionUnknown,
    submissionError,
    activeOwnerId,
    synchronizeAccess,
    find,
    forAfterSale,
    load,
    loadOne,
    submitShipment,
  };
});
