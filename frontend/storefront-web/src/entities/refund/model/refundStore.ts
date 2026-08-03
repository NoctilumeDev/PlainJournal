import { ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createPaymentApi,
  type BusinessId,
  type PaymentApi,
  type Refund,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface RefundAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveRefundAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

class RefundResponseMismatchError extends Error {
  constructor() {
    super("Payment 已响应，但返回的退款事实与本次账户或售后记录不一致。");
    this.name = "RefundResponseMismatchError";
  }
}

function isActiveContext(context: RefundAccessContext): context is {
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

function paymentApi(accessToken: string): PaymentApi {
  return createPaymentApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

export const useRefundsStore = defineStore("customer-refunds", () => {
  const refunds = ref<Refund[]>([]);
  const loadingAfterSaleNo = ref<string | null>(null);
  const error = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let requestRevision = 0;

  function synchronizeAccess(context: RefundAccessContext): ActiveRefundAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;
    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      requestRevision += 1;
      loadingAfterSaleNo.value = null;
      error.value = null;
      if (ownerChanged) {
        refunds.value = [];
      }
    }
    if (!isActiveContext(context)) {
      refunds.value = [];
      return null;
    }
    return { ownerId: context.ownerId, accessToken: context.accessToken, revision: accessRevision };
  }

  function accessIsCurrent(access: ActiveRefundAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function forAfterSale(afterSaleNo: string): Refund | null {
    return refunds.value.find((value) => value.afterSaleNo === afterSaleNo) ?? null;
  }

  function verifyFact(access: ActiveRefundAccess, value: Refund, afterSaleNo: string) {
    if (value.userId !== access.ownerId || value.afterSaleNo !== afterSaleNo) {
      throw new RefundResponseMismatchError();
    }
  }

  function upsert(value: Refund) {
    const index = refunds.value.findIndex((candidate) => candidate.refundNo === value.refundNo);
    if (index >= 0) {
      refunds.value[index] = value;
    } else {
      refunds.value.unshift(value);
    }
  }

  async function loadByAfterSale(
    context: RefundAccessContext,
    afterSaleNo: string,
    silentNotFound = true,
  ): Promise<Refund | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const currentRevision = ++requestRevision;
    loadingAfterSaleNo.value = afterSaleNo;
    error.value = null;
    try {
      const value = await paymentApi(access.accessToken).refundByAfterSale(afterSaleNo);
      if (!accessIsCurrent(access) || currentRevision !== requestRevision) {
        return null;
      }
      verifyFact(access, value, afterSaleNo);
      upsert(value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || currentRevision !== requestRevision) {
        return null;
      }
      if (silentNotFound && cause instanceof ApiError && cause.status === 404) {
        return null;
      }
      error.value = cause instanceof Error ? cause.message : "退款事实暂时无法读取。";
      return null;
    } finally {
      if (accessIsCurrent(access) && currentRevision === requestRevision) {
        loadingAfterSaleNo.value = null;
      }
    }
  }

  return {
    refunds,
    loadingAfterSaleNo,
    error,
    activeOwnerId,
    synchronizeAccess,
    forAfterSale,
    loadByAfterSale,
  };
});
