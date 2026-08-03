import type { Refund } from "@plain-journal/foundation";

export interface RefundStatusPresentation {
  label: string;
  detail: string;
  tone: "processing" | "refunded" | "attention" | "neutral";
}

export function refundStatusPresentation(refund: Refund): RefundStatusPresentation {
  if (refund.status === "SUCCESS") {
    return {
      label: "退款成功",
      detail: "Payment 已确认渠道退款成功。",
      tone: "refunded",
    };
  }
  if (refund.status === "FAILED" || refund.requestStatus === "NEEDS_ATTENTION") {
    return {
      label: "需要处理",
      detail: "自动派发未能收敛，需要平台在授权与审计边界内治理。",
      tone: "attention",
    };
  }
  if (refund.status === "PROCESSING") {
    return {
      label: "退款处理中",
      detail: "渠道尚未返回明确成功，页面不会提前显示到账。",
      tone: "processing",
    };
  }
  return {
    label: refund.status,
    detail: "请以 Payment 返回的退款事实为准。",
    tone: "neutral",
  };
}
