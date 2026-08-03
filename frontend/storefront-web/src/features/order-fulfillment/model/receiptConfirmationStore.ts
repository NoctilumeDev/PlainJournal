import { ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createFulfillmentApi,
  type BusinessId,
  type Fulfillment,
  type FulfillmentApi,
} from "@plain-journal/foundation";

import {
  FulfillmentAccessChangedError,
  FulfillmentResponseMismatchError,
  useFulfillmentsStore,
  type FulfillmentAccessContext,
} from "../../../entities/fulfillment";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const CONFIRMABLE_STATUSES = new Set(["SHIPPED", "IN_TRANSIT", "DELIVERING"]);

interface ActiveReceiptAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

function isActiveContext(context: FulfillmentAccessContext): context is {
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

function isUncertainConfirmationFailure(cause: unknown): boolean {
  return cause instanceof FulfillmentResponseMismatchError
    || (cause instanceof ApiError && (
      cause.kind === "network"
      || cause.kind === "timeout"
      || cause.kind === "invalid-response"
      || (cause.kind === "http" && (cause.status ?? 500) >= 500)
    ));
}

export const useReceiptConfirmationStore = defineStore(
  "customer-receipt-confirmation",
  () => {
    const confirmingOrderNo = ref<string | null>(null);
    const unknownOrderNo = ref<string | null>(null);
    const confirmationError = ref<string | null>(null);
    const activeOwnerId = ref<BusinessId | null>(null);
    const fulfillments = useFulfillmentsStore();
    let activeAccessToken: string | null = null;
    let accessRevision = 0;
    let confirmationRevision = 0;
    let activeConfirmationPromise: Promise<Fulfillment | null> | null = null;
    let activeConfirmationOrderNo: string | null = null;
    let activeConfirmationAccessRevision = -1;

    function synchronizeAccess(
      context: FulfillmentAccessContext,
    ): ActiveReceiptAccess | null {
      fulfillments.synchronizeAccess(context);
      const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
      const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
      const ownerChanged = activeOwnerId.value !== nextOwnerId;
      const tokenChanged = activeAccessToken !== nextAccessToken;

      if (ownerChanged || tokenChanged) {
        activeOwnerId.value = nextOwnerId;
        activeAccessToken = nextAccessToken;
        accessRevision += 1;
        confirmationRevision += 1;
        activeConfirmationPromise = null;
        activeConfirmationOrderNo = null;
        activeConfirmationAccessRevision = -1;
        confirmingOrderNo.value = null;
        unknownOrderNo.value = null;
        confirmationError.value = null;
      }

      if (!isActiveContext(context)) {
        return null;
      }
      return {
        ownerId: context.ownerId,
        accessToken: context.accessToken,
        revision: accessRevision,
      };
    }

    function accessIsCurrent(access: ActiveReceiptAccess): boolean {
      return access.revision === accessRevision
        && access.ownerId === activeOwnerId.value
        && access.accessToken === activeAccessToken;
    }

    function requireCurrent(access: ActiveReceiptAccess) {
      if (!accessIsCurrent(access)) {
        throw new FulfillmentAccessChangedError();
      }
    }

    function resolveFromFact(
      context: FulfillmentAccessContext,
      value: Fulfillment | null,
    ) {
      const access = synchronizeAccess(context);
      if (!access || !value || value.userId !== access.ownerId) {
        return;
      }
      if (value.status === "SIGNED" && unknownOrderNo.value === value.orderNo) {
        unknownOrderNo.value = null;
        confirmationError.value = null;
      }
    }

    function markUnknown(orderNo: string, message: string) {
      unknownOrderNo.value = orderNo;
      confirmationError.value = message;
    }

    function completeConfirmation(
      context: FulfillmentAccessContext,
      access: ActiveReceiptAccess,
      orderNo: string,
      value: Fulfillment,
    ): Fulfillment {
      requireCurrent(access);
      fulfillments.recordAuthoritativeFulfillment(context, orderNo, value);
      if (value.status !== "SIGNED") {
        markUnknown(
          orderNo,
          "确认接口已响应，但 Fulfillment 尚未返回 SIGNED。",
        );
        return value;
      }
      unknownOrderNo.value = null;
      confirmationError.value = null;
      return value;
    }

    function confirmReceipt(
      context: FulfillmentAccessContext,
      orderNo: string,
    ): Promise<Fulfillment | null> {
      const access = synchronizeAccess(context);
      if (!access) {
        confirmationError.value = "当前会话没有可用于确认收货的用户事实。";
        return Promise.resolve(null);
      }
      if (
        activeConfirmationPromise
        && activeConfirmationOrderNo === orderNo
        && activeConfirmationAccessRevision === access.revision
      ) {
        return activeConfirmationPromise;
      }

      const request = confirmReceiptWithAccess(context, access, orderNo);
      activeConfirmationPromise = request;
      activeConfirmationOrderNo = orderNo;
      activeConfirmationAccessRevision = access.revision;
      const clearActiveConfirmation = () => {
        if (
          activeConfirmationPromise === request
          && activeConfirmationAccessRevision === access.revision
        ) {
          activeConfirmationPromise = null;
          activeConfirmationOrderNo = null;
          activeConfirmationAccessRevision = -1;
        }
      };
      void request.then(clearActiveConfirmation, clearActiveConfirmation);
      return request;
    }

    async function confirmReceiptWithAccess(
      context: FulfillmentAccessContext,
      access: ActiveReceiptAccess,
      orderNo: string,
    ): Promise<Fulfillment | null> {
      confirmationError.value = null;
      const current = fulfillments.fulfillmentForOrder(orderNo)
        ?? await fulfillments.loadForOrder(context, orderNo, false);
      requireCurrent(access);
      if (!current) {
        confirmationError.value = fulfillments.error
          ?? "当前订单没有可确认的履约事实。";
        return null;
      }
      if (current.status === "SIGNED") {
        unknownOrderNo.value = null;
        return current;
      }
      if (!CONFIRMABLE_STATUSES.has(current.status)) {
        unknownOrderNo.value = null;
        confirmationError.value = `履约当前为 ${current.status}，不能确认收货。`;
        return current;
      }

      const requestRevision = ++confirmationRevision;
      confirmingOrderNo.value = orderNo;
      try {
        const value = await fulfillmentApi(access.accessToken)
          .confirmReceipt(orderNo);
        requireCurrent(access);
        if (requestRevision !== confirmationRevision) {
          return null;
        }
        return completeConfirmation(context, access, orderNo, value);
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          throw new FulfillmentAccessChangedError();
        }
        if (requestRevision !== confirmationRevision) {
          return null;
        }
        if (isUncertainConfirmationFailure(cause)) {
          const recovered = await fulfillments.loadForOrder(context, orderNo, false);
          requireCurrent(access);
          if (recovered?.status === "SIGNED") {
            unknownOrderNo.value = null;
            confirmationError.value = null;
            return recovered;
          }
          markUnknown(
            orderNo,
            "确认收货结果尚未确认。请先查询履约事实，仍未确认时可安全重试同一路径。",
          );
          return recovered;
        }
        unknownOrderNo.value = null;
        confirmationError.value = cause instanceof Error
          ? cause.message
          : "确认收货未完成。";
        return null;
      } finally {
        if (
          accessIsCurrent(access)
          && requestRevision === confirmationRevision
        ) {
          confirmingOrderNo.value = null;
        }
      }
    }

    return {
      confirmingOrderNo,
      unknownOrderNo,
      confirmationError,
      activeOwnerId,
      synchronizeAccess,
      resolveFromFact,
      confirmReceipt,
    };
  },
);
