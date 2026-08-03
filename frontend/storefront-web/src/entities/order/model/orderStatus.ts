import type { Order } from "@plain-journal/foundation";

export interface OrderStatusPresentation {
  label: string;
  title: string;
  detail: string;
  tone: "active" | "success" | "warning" | "muted";
}

export function orderStatusPresentation(order: Order): OrderStatusPresentation {
  switch (order.status) {
    case "PENDING_STOCK":
      return {
        label: "处理中",
        title: "订单正在处理中",
        detail: "Trade 已保存订单事实，正在推进优惠锁定与库存预占。当前状态不能解释为失败，可以刷新查询。",
        tone: "active",
      };
    case "PENDING_PAYMENT":
      return {
        label: "待支付",
        title: "库存已经预占",
        detail: "订单已进入待支付状态。可以创建或查询 Payment 支付单；订单创建本身仍不代表支付成功。",
        tone: "active",
      };
    case "CANCELING":
      return {
        label: "取消中",
        title: "订单正在取消",
        detail: "库存与营销权益的释放正在推进，完成前不会提前显示取消成功。",
        tone: "warning",
      };
    case "CANCELED":
      return {
        label: "已取消",
        title: "订单已取消",
        detail: "Trade 已完成取消流程。",
        tone: "muted",
      };
    case "CLOSED":
      return {
        label: "已关闭",
        title: "订单已经关闭",
        detail: order.closeReason
          ? `关闭原因：${order.closeReason}`
          : "订单已由 Trade 状态机关闭。",
        tone: "muted",
      };
    case "PAID":
      return {
        label: "已支付",
        title: "支付事实已确认",
        detail: "Trade 已收到支付成功事实，后续由履约流程继续推进。",
        tone: "success",
      };
    case "FULFILLING":
      return {
        label: "履约中",
        title: "订单正在履约",
        detail: "履约单已建立，正在等待发货。",
        tone: "active",
      };
    case "SHIPPED":
      return {
        label: "运输中",
        title: "订单已发货",
        detail: "物流正在运输途中。",
        tone: "active",
      };
    case "COMPLETED":
      return {
        label: "已完成",
        title: "订单已完成",
        detail: "签收事实已经确认。",
        tone: "success",
      };
    case "PAYMENT_EXCEPTION":
      return {
        label: "待核对",
        title: "订单需要人工核对",
        detail: "支付与订单状态存在需要治理的异常，不会在前端伪造成功。",
        tone: "warning",
      };
    default:
      return {
        label: "待确认",
        title: "订单状态待确认",
        detail: "请刷新订单状态，以 Trade 返回的最终事实为准。",
        tone: "warning",
      };
  }
}
