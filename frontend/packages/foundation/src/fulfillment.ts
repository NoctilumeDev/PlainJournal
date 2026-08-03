import type { ApiClient, BusinessId } from "./api";

export interface FulfillmentAddress {
  sourceAddressId: BusinessId;
  recipientName: string;
  phone: string;
  province: string;
  provinceCode: string | null;
  city: string;
  cityCode: string | null;
  district: string;
  districtCode: string | null;
  detailAddress: string;
  postalCode: string | null;
}

export interface FulfillmentStatusHistory {
  fromStatus: string | null;
  toStatus: string;
  command: string;
  reason: string | null;
  operatorType: string;
  operatorId: string;
  createdAt: string;
}

export interface LogisticsTrace {
  externalEventId: string;
  nodeType: string;
  description: string;
  locationName: string | null;
  longitude: string | number | null;
  latitude: string | number | null;
  occurredAt: string;
}

export interface Fulfillment {
  fulfillmentNo: string;
  orderNo: string;
  userId: BusinessId;
  deliveryAddress: FulfillmentAddress;
  status: string;
  carrier: string | null;
  trackingNo: string | null;
  history: FulfillmentStatusHistory[];
  traces: LogisticsTrace[];
  version: number;
  createdAt: string;
  updatedAt: string;
  pickedAt: string | null;
  packedAt: string | null;
  shippedAt: string | null;
  signedAt: string | null;
}

export interface ShipmentPosition {
  fulfillmentNo: string;
  orderNo: string;
  externalEventId: string;
  nodeType: string;
  locationName: string | null;
  longitude: string | number;
  latitude: string | number;
  occurredAt: string;
}

export interface NearbyShipmentPosition {
  fulfillmentNo: string;
  orderNo: string;
  userId: BusinessId;
  status: string;
  nodeType: string;
  locationName: string | null;
  longitude: string | number;
  latitude: string | number;
  distanceMeters: string | number;
  occurredAt: string;
}

export interface ShipmentGeoQuery {
  longitude: string | number;
  latitude: string | number;
  radiusMeters?: number;
  limit?: number;
}

export interface ShipmentGeoCacheRebuild {
  scanned: number;
  cached: number;
}

export interface ReturnItem {
  lineNo: number;
  skuId: BusinessId;
  quantity: number;
  refundableAmount: string | number;
}

export interface ReturnReceipt {
  returnReceiptNo: string;
  afterSaleNo: string;
  orderNo: string;
  userId: BusinessId;
  warehouseId: BusinessId;
  reservationNo: string;
  status: string;
  refundAmount: string | number;
  carrier: string | null;
  trackingNo: string | null;
  inspectionRemark: string | null;
  items: ReturnItem[];
  version: number;
  createdAt: string;
  updatedAt: string;
  shippedAt: string | null;
  receivedAt: string | null;
  inspectedAt: string | null;
}

export interface ShipFulfillmentInput {
  carrier: string;
  trackingNo: string;
}

export interface AddLogisticsTraceInput {
  externalEventId: string;
  nodeType: "TRANSIT" | "DELIVERING" | "SIGNED" | "EXCEPTION";
  description: string;
  locationName?: string | null;
  longitude?: string | number | null;
  latitude?: string | number | null;
  occurredAt: string;
}

export interface FulfillmentApi {
  fulfillmentByOrder(orderNo: string): Promise<Fulfillment>;
  shipmentPosition(orderNo: string): Promise<ShipmentPosition>;
  confirmReceipt(orderNo: string): Promise<Fulfillment>;
  returns(): Promise<ReturnReceipt[]>;
  returnReceipt(returnReceiptNo: string): Promise<ReturnReceipt>;
  submitReturnShipment(
    returnReceiptNo: string,
    carrier: string,
    trackingNo: string,
  ): Promise<ReturnReceipt>;
  adminFulfillments(status?: string): Promise<Fulfillment[]>;
  adminFulfillment(fulfillmentNo: string): Promise<Fulfillment>;
  startPicking(fulfillmentNo: string): Promise<Fulfillment>;
  markPacked(fulfillmentNo: string): Promise<Fulfillment>;
  shipFulfillment(fulfillmentNo: string, input: ShipFulfillmentInput): Promise<Fulfillment>;
  addLogisticsTrace(
    fulfillmentNo: string,
    input: AddLogisticsTraceInput,
  ): Promise<Fulfillment>;
  markFulfillmentException(fulfillmentNo: string, reason: string): Promise<Fulfillment>;
  resolveFulfillmentException(
    fulfillmentNo: string,
    commandId: string,
    reason: string,
  ): Promise<Fulfillment>;
  nearbyShipmentPositions(query: ShipmentGeoQuery): Promise<NearbyShipmentPosition[]>;
  rebuildShipmentGeoCache(limit?: number): Promise<ShipmentGeoCacheRebuild>;
  adminReturns(status?: string): Promise<ReturnReceipt[]>;
  adminReturn(returnReceiptNo: string): Promise<ReturnReceipt>;
  receiveReturn(returnReceiptNo: string): Promise<ReturnReceipt>;
  inspectReturn(returnReceiptNo: string, remark: string): Promise<ReturnReceipt>;
}

export function createFulfillmentApi(client: ApiClient): FulfillmentApi {
  return {
    fulfillmentByOrder(orderNo) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/orders/${encodeURIComponent(orderNo)}`,
      );
    },
    shipmentPosition(orderNo) {
      return client.request<ShipmentPosition>(
        `/api/v1/fulfillment/orders/${encodeURIComponent(orderNo)}/position`,
      );
    },
    confirmReceipt(orderNo) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/orders/${encodeURIComponent(orderNo)}/confirm-receipt`,
        { method: "POST" },
      );
    },
    returns() {
      return client.request<ReturnReceipt[]>("/api/v1/fulfillment/returns");
    },
    returnReceipt(returnReceiptNo) {
      return client.request<ReturnReceipt>(
        `/api/v1/fulfillment/returns/${encodeURIComponent(returnReceiptNo)}`,
      );
    },
    submitReturnShipment(returnReceiptNo, carrier, trackingNo) {
      return client.request<ReturnReceipt>(
        `/api/v1/fulfillment/returns/${encodeURIComponent(returnReceiptNo)}/shipment`,
        {
          method: "POST",
          body: JSON.stringify({ carrier, trackingNo }),
        },
      );
    },
    adminFulfillments(status) {
      const query = status ? `?status=${encodeURIComponent(status)}` : "";
      return client.request<Fulfillment[]>(
        `/api/v1/fulfillment/admin/orders${query}`,
      );
    },
    adminFulfillment(fulfillmentNo) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}`,
      );
    },
    startPicking(fulfillmentNo) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/picking`,
        { method: "POST" },
      );
    },
    markPacked(fulfillmentNo) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/packed`,
        { method: "POST" },
      );
    },
    shipFulfillment(fulfillmentNo, input) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/ship`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
    addLogisticsTrace(fulfillmentNo, input) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/traces`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
    markFulfillmentException(fulfillmentNo, reason) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/exception`,
        {
          method: "POST",
          body: JSON.stringify({ reason }),
        },
      );
    },
    resolveFulfillmentException(fulfillmentNo, commandId, reason) {
      return client.request<Fulfillment>(
        `/api/v1/fulfillment/admin/orders/${encodeURIComponent(fulfillmentNo)}/exception/resolve`,
        {
          method: "POST",
          headers: { "Idempotency-Key": commandId },
          body: JSON.stringify({ reason }),
        },
      );
    },
    nearbyShipmentPositions(query) {
      const parameters = new URLSearchParams({
        longitude: String(query.longitude),
        latitude: String(query.latitude),
        radiusMeters: String(query.radiusMeters ?? 50000),
        limit: String(query.limit ?? 50),
      });
      return client.request<NearbyShipmentPosition[]>(
        `/api/v1/fulfillment/admin/geo/nearby?${parameters.toString()}`,
      );
    },
    rebuildShipmentGeoCache(limit = 5000) {
      return client.request<ShipmentGeoCacheRebuild>(
        `/api/v1/fulfillment/admin/geo/cache/rebuild?limit=${encodeURIComponent(limit)}`,
        { method: "POST" },
      );
    },
    adminReturns(status) {
      const query = status ? `?status=${encodeURIComponent(status)}` : "";
      return client.request<ReturnReceipt[]>(
        `/api/v1/fulfillment/admin/returns${query}`,
      );
    },
    adminReturn(returnReceiptNo) {
      return client.request<ReturnReceipt>(
        `/api/v1/fulfillment/admin/returns/${encodeURIComponent(returnReceiptNo)}`,
      );
    },
    receiveReturn(returnReceiptNo) {
      return client.request<ReturnReceipt>(
        `/api/v1/fulfillment/admin/returns/${encodeURIComponent(returnReceiptNo)}/receive`,
        { method: "POST" },
      );
    },
    inspectReturn(returnReceiptNo, remark) {
      return client.request<ReturnReceipt>(
        `/api/v1/fulfillment/admin/returns/${encodeURIComponent(returnReceiptNo)}/inspect`,
        {
          method: "POST",
          body: JSON.stringify({ remark }),
        },
      );
    },
  };
}
