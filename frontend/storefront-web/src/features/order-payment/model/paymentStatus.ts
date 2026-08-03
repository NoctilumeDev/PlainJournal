import type { Payment } from "@plain-journal/foundation";

export interface PaymentStatusPresentation {
  label: string;
  title: string;
  detail: string;
  tone: "active" | "success" | "warning" | "muted";
}

export function paymentStatusPresentation(payment: Payment): PaymentStatusPresentation {
  switch (payment.status) {
    case "PROCESSING":
      return {
        label: "处理中",
        title: "支付结果正在确认",
        detail: "Payment 已保存支付单，模拟渠道仍在等待独立回调。当前状态不能解释为支付成功或失败。",
        tone: "active",
      };
    case "SUCCESS":
      return {
        label: "已支付",
        title: "支付成功",
        detail: "Payment 已确认有效成功回调。Trade 与履约状态可能仍在通过事件收敛，可以继续刷新订单。",
        tone: "success",
      };
    case "FAILED":
      return {
        label: "支付失败",
        title: "渠道已明确失败",
        detail: "Payment 已保存渠道失败事实，没有扣款成功。可以保留订单并再次查询。",
        tone: "warning",
      };
    default:
      return {
        label: "待确认",
        title: "支付状态待确认",
        detail: "请刷新 Payment 权威事实，未知状态不会显示为支付成功。",
        tone: "warning",
      };
  }
}
