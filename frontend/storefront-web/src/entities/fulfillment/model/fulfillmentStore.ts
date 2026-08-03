import { ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createFulfillmentApi,
  type BusinessId,
  type Fulfillment,
  type FulfillmentApi,
  type ShipmentPosition,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface FulfillmentAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveFulfillmentAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export class FulfillmentAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的履约请求结果不会写入当前页面。");
    this.name = "FulfillmentAccessChangedError";
  }
}

export class FulfillmentResponseMismatchError extends Error {
  constructor() {
    super("Fulfillment 已响应，但返回的履约事实与本次订单或账户不一致。");
    this.name = "FulfillmentResponseMismatchError";
  }
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

export const useFulfillmentsStore = defineStore("customer-fulfillments", () => {
  const fulfillments = ref<Fulfillment[]>([]);
  const positions = ref<ShipmentPosition[]>([]);
  const loadingOrderNo = ref<string | null>(null);
  const loadingPositionOrderNo = ref<string | null>(null);
  const error = ref<string | null>(null);
  const positionError = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let fulfillmentReadRevision = 0;
  let positionReadRevision = 0;

  function synchronizeAccess(
    context: FulfillmentAccessContext,
  ): ActiveFulfillmentAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const tokenChanged = activeAccessToken !== nextAccessToken;

    if (ownerChanged || tokenChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      fulfillmentReadRevision += 1;
      positionReadRevision += 1;
      loadingOrderNo.value = null;
      loadingPositionOrderNo.value = null;
      error.value = null;
      positionError.value = null;
      if (ownerChanged) {
        fulfillments.value = [];
        positions.value = [];
      }
    }

    if (!isActiveContext(context)) {
      fulfillments.value = [];
      positions.value = [];
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveFulfillmentAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveFulfillmentAccess) {
    if (!accessIsCurrent(access)) {
      throw new FulfillmentAccessChangedError();
    }
  }

  function fulfillmentForOrder(orderNo: string): Fulfillment | null {
    return fulfillments.value.find((value) => value.orderNo === orderNo) ?? null;
  }

  function positionForOrder(orderNo: string): ShipmentPosition | null {
    return positions.value.find((value) => value.orderNo === orderNo) ?? null;
  }

  function removeOrderFacts(orderNo: string) {
    fulfillments.value = fulfillments.value.filter((value) => value.orderNo !== orderNo);
    positions.value = positions.value.filter((value) => value.orderNo !== orderNo);
  }

  function assertFulfillmentIdentity(
    value: Fulfillment,
    access: ActiveFulfillmentAccess,
    orderNo: string,
  ) {
    if (value.orderNo !== orderNo || value.userId !== access.ownerId) {
      throw new FulfillmentResponseMismatchError();
    }
  }

  function assertPositionIdentity(value: ShipmentPosition, orderNo: string) {
    const knownFulfillment = fulfillmentForOrder(orderNo);
    if (
      value.orderNo !== orderNo
      || (
        knownFulfillment
        && value.fulfillmentNo !== knownFulfillment.fulfillmentNo
      )
    ) {
      throw new FulfillmentResponseMismatchError();
    }
  }

  function upsertFulfillment(value: Fulfillment) {
    const index = fulfillments.value.findIndex((candidate) =>
      candidate.fulfillmentNo === value.fulfillmentNo
      || candidate.orderNo === value.orderNo);
    if (index >= 0) {
      fulfillments.value[index] = value;
    } else {
      fulfillments.value.push(value);
    }
  }

  function upsertPosition(value: ShipmentPosition) {
    const index = positions.value.findIndex((candidate) =>
      candidate.orderNo === value.orderNo);
    if (index >= 0) {
      positions.value[index] = value;
    } else {
      positions.value.push(value);
    }
  }

  function recordAuthoritativeFulfillment(
    context: FulfillmentAccessContext,
    orderNo: string,
    value: Fulfillment,
  ): Fulfillment {
    const access = synchronizeAccess(context);
    if (!access) {
      throw new FulfillmentAccessChangedError();
    }
    requireCurrent(access);
    assertFulfillmentIdentity(value, access, orderNo);
    upsertFulfillment(value);
    return value;
  }

  async function loadForOrder(
    context: FulfillmentAccessContext,
    orderNo: string,
    silentNotFound = true,
  ): Promise<Fulfillment | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const requestRevision = ++fulfillmentReadRevision;
    loadingOrderNo.value = orderNo;
    error.value = null;
    try {
      const value = await fulfillmentApi(access.accessToken)
        .fulfillmentByOrder(orderNo);
      requireCurrent(access);
      if (requestRevision !== fulfillmentReadRevision) {
        return null;
      }
      assertFulfillmentIdentity(value, access, orderNo);
      upsertFulfillment(value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new FulfillmentAccessChangedError();
      }
      if (requestRevision !== fulfillmentReadRevision) {
        return null;
      }
      if (cause instanceof ApiError && cause.status === 404) {
        removeOrderFacts(orderNo);
        if (silentNotFound) {
          return null;
        }
        error.value = "履约事实不存在，或不属于当前账户。";
        return null;
      }
      error.value = cause instanceof Error
        ? cause.message
        : "履约事实暂时无法读取。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === fulfillmentReadRevision) {
        loadingOrderNo.value = null;
      }
    }
  }

  async function loadPosition(
    context: FulfillmentAccessContext,
    orderNo: string,
    silentNotFound = true,
  ): Promise<ShipmentPosition | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const requestRevision = ++positionReadRevision;
    loadingPositionOrderNo.value = orderNo;
    positionError.value = null;
    try {
      const value = await fulfillmentApi(access.accessToken)
        .shipmentPosition(orderNo);
      requireCurrent(access);
      if (requestRevision !== positionReadRevision) {
        return null;
      }
      assertPositionIdentity(value, orderNo);
      upsertPosition(value);
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new FulfillmentAccessChangedError();
      }
      if (requestRevision !== positionReadRevision) {
        return null;
      }
      if (cause instanceof ApiError && cause.status === 404) {
        positions.value = positions.value.filter((value) => value.orderNo !== orderNo);
        if (silentNotFound) {
          return null;
        }
        positionError.value = "该订单暂时没有可读取的物流位置。";
        return null;
      }
      positionError.value = cause instanceof Error
        ? cause.message
        : "最新物流位置暂时无法读取。";
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === positionReadRevision) {
        loadingPositionOrderNo.value = null;
      }
    }
  }

  return {
    fulfillments,
    positions,
    loadingOrderNo,
    loadingPositionOrderNo,
    error,
    positionError,
    activeOwnerId,
    synchronizeAccess,
    fulfillmentForOrder,
    positionForOrder,
    recordAuthoritativeFulfillment,
    loadForOrder,
    loadPosition,
  };
});
