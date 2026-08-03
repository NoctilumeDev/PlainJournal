import type { ApiClient, BusinessId } from "./api";

export interface StockSummary {
  skuId: BusinessId;
  onHand: number;
  reserved: number;
  available: number;
}

export interface Warehouse {
  id: BusinessId;
  code: string;
  name: string;
  status: string;
  version: number;
}

export interface StockPosition extends StockSummary {
  warehouseId: BusinessId;
  version: number;
}

export interface AdjustStockInput {
  movementNo: string;
  warehouseId: BusinessId;
  skuId: BusinessId;
  quantityDelta: number;
  reason: string;
}

export interface InventoryApi {
  stock(skuId: BusinessId): Promise<StockSummary>;
  warehouses(): Promise<Warehouse[]>;
  createWarehouse(code: string, name: string): Promise<Warehouse>;
  stockPosition(warehouseId: BusinessId, skuId: BusinessId): Promise<StockPosition>;
  adjustStock(input: AdjustStockInput): Promise<StockPosition>;
}

export function createInventoryApi(client: ApiClient): InventoryApi {
  return {
    stock(skuId) {
      return client.request<StockSummary>(
        `/api/v1/inventory/stocks/${encodeURIComponent(skuId)}`,
      );
    },
    warehouses() {
      return client.request<Warehouse[]>("/api/v1/inventory/admin/warehouses");
    },
    createWarehouse(code, name) {
      return client.request<Warehouse>("/api/v1/inventory/admin/warehouses", {
        method: "POST",
        body: JSON.stringify({ code, name }),
      });
    },
    stockPosition(warehouseId, skuId) {
      return client.request<StockPosition>(
        `/api/v1/inventory/admin/warehouses/${encodeURIComponent(warehouseId)}`
        + `/stocks/${encodeURIComponent(skuId)}`,
      );
    },
    adjustStock(input) {
      return client.request<StockPosition>("/api/v1/inventory/admin/stocks/adjustments", {
        method: "POST",
        body: JSON.stringify(input),
      });
    },
  };
}
