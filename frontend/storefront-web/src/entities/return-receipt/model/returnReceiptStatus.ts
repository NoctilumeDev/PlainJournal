import type { ReturnReceipt } from "@plain-journal/foundation";

export interface ReturnReceiptStatusPresentation {
  label: string;
  detail: string;
  tone: "warning" | "processing" | "success" | "neutral";
}

export function returnReceiptStatusPresentation(
  receipt: ReturnReceipt,
): ReturnReceiptStatusPresentation {
  switch (receipt.status) {
    case "WAIT_SHIPMENT":
      return {
        label: "等待寄回",
        detail: "顾客尚未提交真实承运商与运单号。",
        tone: "warning",
      };
    case "RETURNING":
      return {
        label: "退货途中",
        detail: "运单已经保存，仓库尚未确认收货。",
        tone: "processing",
      };
    case "RECEIVED":
      return {
        label: "仓库已收货",
        detail: "Fulfillment 已确认收货，仍待验收。",
        tone: "processing",
      };
    case "INSPECTED":
      return {
        label: "仓库已验收",
        detail: "验收事实已经确认，后续由库存与退款事件推进。",
        tone: "success",
      };
    default:
      return {
        label: receipt.status,
        detail: "请以 Fulfillment 返回的退货事实为准。",
        tone: "neutral",
      };
  }
}
