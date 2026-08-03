import type { Fulfillment, FulfillmentStatusHistory } from "@plain-journal/foundation";

export interface FulfillmentStatusPresentation {
  label: string;
  title: string;
  detail: string;
  tone: "active" | "success" | "warning" | "muted";
}

export function fulfillmentStatusPresentation(
  fulfillment: Fulfillment,
): FulfillmentStatusPresentation {
  switch (fulfillment.status) {
    case "CREATED":
      return {
        label: "待拣货",
        title: "履约单已经建立",
        detail: "Payment 成功事实已推进到 Fulfillment，仓库尚未开始拣货。",
        tone: "active",
      };
    case "PICKING":
      return {
        label: "拣货中",
        title: "仓库正在拣货",
        detail: "商品正在按订单快照准备，尚未完成打包。",
        tone: "active",
      };
    case "PACKED":
      return {
        label: "已打包",
        title: "包裹等待发出",
        detail: "仓库已完成打包，承运商和运单将在发货后显示。",
        tone: "active",
      };
    case "SHIPPED":
      return {
        label: "已发货",
        title: "包裹已经发出",
        detail: "发货事实已经确认，物流轨迹会按承运商事件追加。",
        tone: "active",
      };
    case "IN_TRANSIT":
      return {
        label: "运输中",
        title: "包裹正在运输",
        detail: "物流节点是追加事实，刷新页面可读取最新轨迹。",
        tone: "active",
      };
    case "DELIVERING":
      return {
        label: "派送中",
        title: "包裹正在派送",
        detail: "确认实际收到商品后，才使用确认收货动作。",
        tone: "active",
      };
    case "SIGNED":
      return {
        label: "已签收",
        title: "签收事实已经确认",
        detail: "Fulfillment 已记录签收，Trade 将通过消息最终收敛订单状态。",
        tone: "success",
      };
    case "EXCEPTION":
      return {
        label: "异常",
        title: "履约需要处理",
        detail: "异常事实已被保留，不会把未完成履约伪装成已签收。",
        tone: "warning",
      };
    case "CANCELED":
      return {
        label: "已取消",
        title: "履约已经取消",
        detail: "当前履约单不再继续推进。",
        tone: "muted",
      };
    default:
      return {
        label: "待确认",
        title: "履约状态待确认",
        detail: "请刷新 Fulfillment 状态，以服务端事实为准。",
        tone: "warning",
      };
  }
}

export function fulfillmentHistoryLabel(item: FulfillmentStatusHistory): string {
  const labels: Record<string, string> = {
    CREATED: "履约单建立",
    PICKING: "开始拣货",
    PACKED: "完成打包",
    SHIPPED: "包裹发出",
    IN_TRANSIT: "运输途中",
    DELIVERING: "正在派送",
    SIGNED: "确认签收",
    EXCEPTION: "履约异常",
    CANCELED: "履约取消",
  };
  return labels[item.toStatus] ?? item.toStatus;
}
