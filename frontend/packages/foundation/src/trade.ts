import type { ApiClient, BusinessId, PageResponse } from "./api";

export interface CartItem {
  id: BusinessId;
  productId: BusinessId;
  skuId: BusinessId;
  productTitle: string;
  skuName: string;
  specJson: string;
  unitPrice: string | number;
  quantity: number;
  selected: boolean;
}

export interface GuestBagMergeItem {
  productId: BusinessId;
  skuId: BusinessId;
  quantity: number;
}

export interface PutCartItemInput {
  productId: BusinessId;
  quantity: number;
  selected: boolean;
}

export interface CreateOrderItem {
  productId: BusinessId;
  skuId: BusinessId;
  quantity: number;
}

export interface CreateOrderInput {
  addressId: BusinessId;
  items: CreateOrderItem[];
  benefitNos: string[];
}

export interface OrderAddress {
  sourceAddressId: BusinessId;
  recipientName: string;
  phone: string;
  province: string;
  provinceCode: string;
  city: string;
  cityCode: string;
  district: string;
  districtCode: string;
  detailAddress: string;
  postalCode: string | null;
}

export interface OrderItem {
  lineNo: number;
  productId: BusinessId;
  skuId: BusinessId;
  productTitle: string;
  skuCode: string;
  skuName: string;
  specJson: string;
  imageObjectKey: string | null;
  unitPrice: string | number;
  quantity: number;
  lineAmount: string | number;
  discountAmount: string | number;
  payableAmount: string | number;
}

export interface OrderDiscountAllocation {
  lineNo: number;
  skuId: BusinessId;
  benefitNo: string;
  ruleCode: string;
  benefitType: string;
  discountAmount: string | number;
}

export interface OrderPriceSnapshot {
  marketingLockNo: string;
  originalAmount: string | number;
  couponDiscount: string | number;
  redPacketDiscount: string | number;
  subsidyDiscount: string | number;
  discountAmount: string | number;
  payableAmount: string | number;
  pricingVersion: string;
  allocations: OrderDiscountAllocation[];
}

export interface Order {
  orderNo: string;
  status: string;
  totalAmount: string | number;
  priceSnapshot: OrderPriceSnapshot | null;
  paymentDeadline: string;
  closeReason: string | null;
  deliveryAddress: OrderAddress;
  items: OrderItem[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface AfterSaleItem {
  lineNo: number;
  skuId: BusinessId;
  productTitle: string;
  skuName: string;
  quantity: number;
  lineAmount: string | number;
  discountAmount: string | number;
  refundableAmount: string | number;
}

export interface AfterSale {
  afterSaleNo: string;
  orderNo: string;
  userId: BusinessId;
  afterSaleType: string;
  status: string;
  reason: string;
  reviewReason: string | null;
  refundAmount: string | number;
  returnReceiptNo: string | null;
  refundNo: string | null;
  items: AfterSaleItem[];
  version: number;
  createdAt: string;
  updatedAt: string;
  approvedAt: string | null;
  completedAt: string | null;
}

export interface ReviewAfterSaleInput {
  approved: boolean;
  reason: string;
}

export interface TradeApi {
  cartItems(): Promise<CartItem[]>;
  putCartItem(skuId: BusinessId, input: PutCartItemInput): Promise<CartItem>;
  removeCartItem(skuId: BusinessId): Promise<void>;
  mergeGuestBag(items: GuestBagMergeItem[], idempotencyKey: string): Promise<CartItem[]>;
  createOrder(input: CreateOrderInput, idempotencyKey: string): Promise<Order>;
  orders(page?: number, size?: number): Promise<PageResponse<Order>>;
  orderByIdempotencyKey(idempotencyKey: string): Promise<Order>;
  order(orderNo: string): Promise<Order>;
  cancelOrder(orderNo: string): Promise<Order>;
  applyAfterSale(orderNo: string, reason: string, idempotencyKey: string): Promise<AfterSale>;
  afterSales(): Promise<AfterSale[]>;
  afterSale(afterSaleNo: string): Promise<AfterSale>;
  cancelAfterSale(afterSaleNo: string): Promise<AfterSale>;
  adminAfterSales(status?: string): Promise<AfterSale[]>;
  adminAfterSale(afterSaleNo: string): Promise<AfterSale>;
  reviewAfterSale(afterSaleNo: string, input: ReviewAfterSaleInput): Promise<AfterSale>;
}

export function createTradeApi(client: ApiClient): TradeApi {
  return {
    cartItems() {
      return client.request<CartItem[]>("/api/v1/trade/cart/items");
    },
    putCartItem(skuId, input) {
      return client.request<CartItem>(
        `/api/v1/trade/cart/items/${encodeURIComponent(skuId)}`,
        {
          method: "PUT",
          body: JSON.stringify(input),
        },
      );
    },
    removeCartItem(skuId) {
      return client.request<void>(
        `/api/v1/trade/cart/items/${encodeURIComponent(skuId)}`,
        {
          method: "DELETE",
        },
      );
    },
    mergeGuestBag(items, idempotencyKey) {
      return client.request<CartItem[]>("/api/v1/trade/cart/guest-merge", {
        method: "POST",
        headers: {
          "Idempotency-Key": idempotencyKey,
        },
        body: JSON.stringify({ items }),
      });
    },
    createOrder(input, idempotencyKey) {
      return client.request<Order>("/api/v1/trade/orders", {
        method: "POST",
        timeoutMs: 15000,
        headers: {
          "Idempotency-Key": idempotencyKey,
        },
        body: JSON.stringify(input),
      });
    },
    orders(page = 1, size = 20) {
      const search = new URLSearchParams({
        page: String(page),
        size: String(size),
      });
      return client.request<PageResponse<Order>>(
        `/api/v1/trade/orders/page?${search.toString()}`,
      );
    },
    orderByIdempotencyKey(idempotencyKey) {
      return client.request<Order>(
        `/api/v1/trade/orders/by-idempotency-key/${encodeURIComponent(idempotencyKey)}`,
      );
    },
    order(orderNo) {
      return client.request<Order>(
        `/api/v1/trade/orders/${encodeURIComponent(orderNo)}`,
      );
    },
    cancelOrder(orderNo) {
      return client.request<Order>(
        `/api/v1/trade/orders/${encodeURIComponent(orderNo)}/cancel`,
        { method: "POST" },
      );
    },
    applyAfterSale(orderNo, reason, idempotencyKey) {
      return client.request<AfterSale>(
        `/api/v1/trade/orders/${encodeURIComponent(orderNo)}/after-sales`,
        {
          method: "POST",
          timeoutMs: 15000,
          headers: {
            "Idempotency-Key": idempotencyKey,
          },
          body: JSON.stringify({ reason }),
        },
      );
    },
    afterSales() {
      return client.request<AfterSale[]>("/api/v1/trade/after-sales");
    },
    afterSale(afterSaleNo) {
      return client.request<AfterSale>(
        `/api/v1/trade/after-sales/${encodeURIComponent(afterSaleNo)}`,
      );
    },
    cancelAfterSale(afterSaleNo) {
      return client.request<AfterSale>(
        `/api/v1/trade/after-sales/${encodeURIComponent(afterSaleNo)}/cancel`,
        { method: "POST" },
      );
    },
    adminAfterSales(status) {
      const query = status ? `?status=${encodeURIComponent(status)}` : "";
      return client.request<AfterSale[]>(`/api/v1/trade/admin/after-sales${query}`);
    },
    adminAfterSale(afterSaleNo) {
      return client.request<AfterSale>(
        `/api/v1/trade/admin/after-sales/${encodeURIComponent(afterSaleNo)}`,
      );
    },
    reviewAfterSale(afterSaleNo, input) {
      return client.request<AfterSale>(
        `/api/v1/trade/admin/after-sales/${encodeURIComponent(afterSaleNo)}/review`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
  };
}
