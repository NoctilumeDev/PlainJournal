import type { AfterSale } from "@plain-journal/foundation";

export interface AfterSaleStatusPresentation {
  label: string;
  title: string;
  detail: string;
  owner: string;
  nextAction: string;
  timing: string;
  tone: "processing" | "success" | "warning" | "attention" | "neutral";
}

export interface AfterSaleProgressStage {
  key: "application" | "return" | "refund" | "complete";
  label: string;
  detail: string;
  state: "completed" | "current" | "upcoming" | "stopped";
}

const STAGES = [
  { key: "application", label: "申请与审核", detail: "Trade 保存申请并形成审核结论" },
  { key: "return", label: "寄回与验收", detail: "Fulfillment 记录寄回、收货与验收" },
  { key: "refund", label: "原路退款", detail: "Payment 派发并确认渠道退款结果" },
  { key: "complete", label: "售后完成", detail: "Trade 消费明确退款成功事实" },
] as const;

export function afterSaleStatusPresentation(
  afterSale: AfterSale,
): AfterSaleStatusPresentation {
  switch (afterSale.status) {
    case "APPLIED":
      return {
        label: "等待审核",
        title: "售后申请已经保存",
        detail: "平台尚未作出审核决定，顾客仍可取消本次申请。",
        owner: "素简记售后审核",
        nextAction: "等待审核；如不再需要售后，可在本页原位取消。",
        timing: "当前没有承诺完成时点，以 Trade 审核事实为准。",
        tone: "processing",
      };
    case "WAIT_RETURN":
      return {
        label: "等待寄回",
        title: "售后审核已经通过",
        detail: "请按退货单填写真实承运商和运单号，仓库收货前不会进入退款。",
        owner: "顾客",
        nextAction: "使用下方退货单提交真实寄回信息。",
        timing: "寄回后以承运商运输与仓库收货事实为准。",
        tone: "warning",
      };
    case "RETURNING":
      return {
        label: "退货途中",
        title: "仓库正在等待退货",
        detail: "寄回信息已经记录，后续以 Fulfillment 收货与验收事实为准。",
        owner: "承运商与 Fulfillment",
        nextAction: "无需重复提交运单；等待仓库收货。",
        timing: "当前没有承运商 SLA 契约，页面不估算到仓时间。",
        tone: "processing",
      };
    case "RECEIVED":
      return {
        label: "仓库已收货",
        title: "退货已经到达仓库",
        detail: "仓库仍需完成验收，库存回补与退款是两个独立事实。",
        owner: "Fulfillment 仓库验收",
        nextAction: "等待仓库验收与库存回补事件推进。",
        timing: "验收时点尚未承诺，以 Fulfillment 更新为准。",
        tone: "processing",
      };
    case "REFUNDING":
      return {
        label: "退款处理中",
        title: "退款请求正在推进",
        detail: "Payment 尚未返回明确成功，页面不会提前显示退款完成。",
        owner: "Payment 与支付渠道",
        nextAction: "无需重复申请；等待渠道返回明确结果。",
        timing: "到账时间由支付渠道决定，当前系统不提供虚假预计时间。",
        tone: "processing",
      };
    case "REFUND_FAILED":
      return {
        label: "需要处理",
        title: "退款派发未能自动收敛",
        detail: "售后事实仍被保留，需要管理员在 Payment 授权补偿与审计边界内处理。",
        owner: "平台财务治理",
        nextAction: "平台核对渠道事实后，通过有授权、有幂等键、有审计的补偿命令恢复。",
        timing: "人工治理完成前不承诺到账时间。",
        tone: "attention",
      };
    case "COMPLETED":
      return {
        label: "售后完成",
        title: "退款已经明确成功",
        detail: "Trade 已消费 Payment 的退款成功事实并完成本次售后。",
        owner: "已完成",
        nextAction: "无需继续操作，可返回订单或售后列表。",
        timing: afterSale.completedAt ? "完成时间以页面记录为准。" : "完成事实已经确认。",
        tone: "success",
      };
    case "REJECTED":
      return {
        label: "审核未通过",
        title: "本次售后申请未通过",
        detail: afterSale.reviewReason || "请查看审核说明。",
        owner: "流程已结束",
        nextAction: "如需进一步说明，可通过客服提交新的事实材料。",
        timing: "本次申请已结束。",
        tone: "neutral",
      };
    case "CANCELED":
      return {
        label: "已取消",
        title: "售后申请已经取消",
        detail: "取消只影响尚未进入退货流程的售后申请。",
        owner: "流程已结束",
        nextAction: "无需继续操作。",
        timing: "本次申请已结束。",
        tone: "neutral",
      };
    default:
      return {
        label: afterSale.status,
        title: "售后状态已更新",
        detail: "请以 Trade、Fulfillment 与 Payment 的独立查询事实为准。",
        owner: "待确认",
        nextAction: "刷新三个所有者域的事实后再决定下一步。",
        timing: "未知状态不提供预计时间。",
        tone: "neutral",
      };
  }
}

export function afterSaleProgress(afterSale: AfterSale): AfterSaleProgressStage[] {
  const stopped = afterSale.status === "REJECTED" || afterSale.status === "CANCELED";
  const currentIndex = afterSale.status === "APPLIED"
    ? 0
    : ["WAIT_RETURN", "RETURNING", "RECEIVED"].includes(afterSale.status)
      ? 1
      : ["REFUNDING", "REFUND_FAILED"].includes(afterSale.status)
        ? 2
        : afterSale.status === "COMPLETED"
          ? 3
          : 0;

  return STAGES.map((stage, index) => ({
    ...stage,
    state: stopped
      ? (index === 0 ? "stopped" : "upcoming")
      : afterSale.status === "COMPLETED" || index < currentIndex
        ? "completed"
        : index === currentIndex
          ? "current"
          : "upcoming",
  }));
}
