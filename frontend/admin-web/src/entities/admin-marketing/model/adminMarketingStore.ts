import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createMarketingApi,
  secureRandomUUID,
  type Benefit,
  type BenefitType,
  type BusinessId,
  type CreateMarketingRuleInput,
  type MarketingApi,
  type MarketingRule,
  type RegionRestriction,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_STORAGE_PREFIX =
  "plain-journal:admin-marketing:pending-command:v1:";

type MarketingCommandKind = "rule" | "grant";
type NumericInput = string | number;

export type MarketingCommandPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

export interface AdminMarketingAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: MarketingApi;
}

interface PendingMarketingCommand {
  kind: MarketingCommandKind;
  referenceNo: string;
  commandKey: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export class MarketingAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的营销请求结果不会写入当前页面。");
    this.name = "MarketingAccessChangedError";
  }
}

export class MarketingContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MarketingContractError";
  }
}

function isActiveContext(
  context: AdminMarketingAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && typeof context.operatorId === "string"
    && context.operatorId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function createApi(accessToken: string): MarketingApi {
  return createMarketingApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function newGrantKey(): string {
  return `admin-benefit:${secureRandomUUID()}`;
}

function defaultRuleForm() {
  const now = Date.now();
  return {
    ruleCode: "",
    name: "",
    benefitType: "COUPON" as BenefitType,
    thresholdAmount: "0.00",
    discountAmount: "0.01",
    stackOrder: 0 as NumericInput,
    validFrom: toLocalDateTime(new Date(now).toISOString()),
    validUntil: toLocalDateTime(
      new Date(now + 30 * 86400000).toISOString(),
    ),
    regionLevel: "" as "" | RegionRestriction["level"],
    regionCode: "",
  };
}

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_STORAGE_PREFIX}${operatorId}`;
}

function parsePending(raw: string | null): PendingMarketingCommand | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("kind" in value)
      || !["rule", "grant"].includes(String(value.kind))
      || !("referenceNo" in value)
      || typeof value.referenceNo !== "string"
      || value.referenceNo.length === 0
      || !("commandKey" in value)
      || typeof value.commandKey !== "string"
      || value.commandKey.length === 0
      || !("payload" in value)
      || !value.payload
      || typeof value.payload !== "object"
      || Array.isArray(value.payload)
      || !("createdAt" in value)
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingMarketingCommand;
  } catch {
    return null;
  }
}

function loadPending(operatorId: BusinessId): PendingMarketingCommand | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(localStorage.getItem(storageKey(operatorId)));
}

function savePending(
  operatorId: BusinessId,
  command: PendingMarketingCommand | null,
) {
  if (typeof localStorage === "undefined") {
    return;
  }
  if (command) {
    localStorage.setItem(storageKey(operatorId), JSON.stringify(command));
  } else {
    localStorage.removeItem(storageKey(operatorId));
  }
}

function resultMayBeUnknown(cause: unknown): boolean {
  if (cause instanceof MarketingContractError) {
    return true;
  }
  if (!(cause instanceof ApiError)) {
    return true;
  }
  return cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500);
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

function stringPayload(
  payload: Record<string, unknown>,
  key: string,
): string {
  const value = payload[key];
  return typeof value === "string" ? value : "";
}

function numberPayload(
  payload: Record<string, unknown>,
  key: string,
): number {
  return Number(payload[key]);
}

function regionsPayload(
  payload: Record<string, unknown>,
): RegionRestriction[] {
  const value = payload.regions;
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((region): region is RegionRestriction =>
      Boolean(
        region
        && typeof region === "object"
        && "level" in region
        && ["PROVINCE", "CITY", "DISTRICT"].includes(String(region.level))
        && "regionCode" in region
        && typeof region.regionCode === "string",
      ))
    .map((region) => ({
      level: region.level,
      regionCode: region.regionCode,
    }));
}

function toLocalDateTime(instant: string): string {
  const date = new Date(instant);
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function normalizedInput(value: unknown): string {
  return String(value ?? "").trim();
}

function moneyMinorUnits(value: string | number): bigint | null {
  const normalized = String(value).trim();
  const match = /^(\d+)(?:\.(\d{1,2}))?$/u.exec(normalized);
  if (!match) {
    return null;
  }
  return (BigInt(match[1] ?? "0") * 100n)
    + BigInt((match[2] ?? "").padEnd(2, "0"));
}

function sameMoney(
  actual: string | number,
  expected: string,
): boolean {
  const actualUnits = moneyMinorUnits(actual);
  const expectedUnits = moneyMinorUnits(expected);
  return actualUnits !== null
    && expectedUnits !== null
    && actualUnits === expectedUnits;
}

function sameInstant(actual: string, expected: string): boolean {
  const actualTime = Date.parse(actual);
  const expectedTime = Date.parse(expected);
  return Number.isFinite(actualTime)
    && Number.isFinite(expectedTime)
    && actualTime === expectedTime;
}

function sameRegions(
  actual: RegionRestriction[],
  expected: RegionRestriction[],
): boolean {
  const serialize = (regions: RegionRestriction[]) =>
    regions
      .map((region) => `${region.level}:${region.regionCode}`)
      .sort()
      .join("|");
  return serialize(actual) === serialize(expected);
}

function ruleInput(
  command: PendingMarketingCommand,
): CreateMarketingRuleInput {
  return {
    ruleCode: stringPayload(command.payload, "ruleCode"),
    name: stringPayload(command.payload, "name"),
    benefitType: stringPayload(
      command.payload,
      "benefitType",
    ) as BenefitType,
    thresholdAmount: stringPayload(command.payload, "thresholdAmount"),
    discountAmount: stringPayload(command.payload, "discountAmount"),
    stackOrder: numberPayload(command.payload, "stackOrder"),
    validFrom: stringPayload(command.payload, "validFrom"),
    validUntil: stringPayload(command.payload, "validUntil"),
    regions: regionsPayload(command.payload),
  };
}

export const useAdminMarketingStore = defineStore("admin-marketing", () => {
  const rule = reactive(defaultRuleForm());
  const grant = reactive({
    userId: "",
    ruleCode: "",
    grantKey: newGrantKey(),
  });
  const createdRule = ref<MarketingRule | null>(null);
  const grantedBenefit = ref<Benefit | null>(null);
  const commandPhase = ref<MarketingCommandPhase>("idle");
  const commandMessage = ref<string | null>(null);
  const pendingCommand = ref<PendingMarketingCommand | null>(null);
  const submitting = ref(false);
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let commandRevision = 0;
  let activeCommandPromise: Promise<MarketingRule | Benefit | null> | null = null;

  const pendingCommandLabel = computed(() => {
    if (pendingCommand.value?.kind === "rule") {
      return "创建营销规则";
    }
    if (pendingCommand.value?.kind === "grant") {
      return "发放用户权益";
    }
    return null;
  });
  const pendingReferenceNo = computed(() =>
    pendingCommand.value?.referenceNo ?? null);
  const commandBlocked = computed(() =>
    submitting.value
    || (commandPhase.value === "unknown" && Boolean(pendingCommand.value)));
  const canRetryPending = computed(() =>
    commandPhase.value === "unknown"
    && pendingCommand.value?.kind === "grant");

  function activeAccess(
    context: AdminMarketingAccessContext,
  ): ActiveAccess | null {
    if (!isActiveContext(context)) {
      return null;
    }
    return {
      operatorId: context.operatorId,
      accessToken: context.accessToken,
      revision: accessRevision,
      api: createApi(context.accessToken),
    };
  }

  function accessIsCurrent(access: ActiveAccess): boolean {
    return access.revision === accessRevision
      && access.operatorId === activeOperatorId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveAccess) {
    if (!accessIsCurrent(access)) {
      throw new MarketingAccessChangedError();
    }
  }

  function hydratePending(command: PendingMarketingCommand | null) {
    if (!command) {
      return;
    }
    if (command.kind === "rule") {
      const input = ruleInput(command);
      Object.assign(rule, {
        ...input,
        validFrom: toLocalDateTime(input.validFrom),
        validUntil: toLocalDateTime(input.validUntil),
        regionLevel: input.regions[0]?.level ?? "",
        regionCode: input.regions[0]?.regionCode ?? "",
      });
      return;
    }
    grant.userId = stringPayload(command.payload, "userId");
    grant.ruleCode = stringPayload(command.payload, "ruleCode");
    grant.grantKey = command.commandKey;
  }

  function synchronizeAccess(context: AdminMarketingAccessContext) {
    const nextOperatorId = isActiveContext(context) ? context.operatorId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const operatorChanged = activeOperatorId.value !== nextOperatorId;
    const accessChanged = operatorChanged || activeAccessToken !== nextAccessToken;
    if (!accessChanged) {
      return activeAccess(context);
    }

    activeOperatorId.value = nextOperatorId;
    activeAccessToken = nextAccessToken;
    accessRevision += 1;
    commandRevision += 1;
    submitting.value = false;
    activeCommandPromise = null;

    if (operatorChanged) {
      Object.assign(rule, defaultRuleForm());
      grant.userId = "";
      grant.ruleCode = "";
      grant.grantKey = newGrantKey();
      createdRule.value = null;
      grantedBenefit.value = null;
      pendingCommand.value = nextOperatorId
        ? loadPending(nextOperatorId)
        : null;
      hydratePending(pendingCommand.value);
      commandPhase.value = pendingCommand.value ? "unknown" : "idle";
      commandMessage.value = pendingCommand.value
        ? pendingCommand.value.kind === "rule"
          ? "发现一条尚未确认的规则创建命令。完整载荷已恢复，但当前 API 没有按规则代码查询的管理端权威读取入口。"
          : "发现一条尚未确认的权益发放命令。顾客、规则和原 grantKey 已恢复，只能原样重试。"
        : null;
    } else if (pendingCommand.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "员工会话凭据已更新，原营销命令继续保持结果未知。";
    }
    return activeAccess(context);
  }

  function requireAccess(
    context: AdminMarketingAccessContext,
    message: string,
  ): ActiveAccess | null {
    const access = synchronizeAccess(context);
    if (!access) {
      commandPhase.value = "rejected";
      commandMessage.value = message;
    }
    return access;
  }

  function beginPending(
    access: ActiveAccess,
    command: PendingMarketingCommand,
  ) {
    pendingCommand.value = command;
    savePending(access.operatorId, command);
    commandPhase.value = "processing";
    commandMessage.value =
      `${pendingCommandLabel.value}正在等待 Marketing 确认。`;
  }

  function settleAccepted(access: ActiveAccess, message: string) {
    pendingCommand.value = null;
    savePending(access.operatorId, null);
    commandPhase.value = "accepted";
    commandMessage.value = message;
  }

  function settleRejected(access: ActiveAccess, message: string) {
    pendingCommand.value = null;
    savePending(access.operatorId, null);
    commandPhase.value = "rejected";
    commandMessage.value = message;
  }

  function validateRule(
    command: PendingMarketingCommand,
    value: MarketingRule,
  ) {
    const input = ruleInput(command);
    if (
      value.ruleCode !== input.ruleCode
      || value.name !== input.name
      || value.benefitType !== input.benefitType
      || !sameMoney(value.thresholdAmount, input.thresholdAmount)
      || !sameMoney(value.discountAmount, input.discountAmount)
      || value.stackOrder !== input.stackOrder
      || !sameInstant(value.validFrom, input.validFrom)
      || !sameInstant(value.validUntil, input.validUntil)
      || !sameRegions(value.regions, input.regions)
    ) {
      throw new MarketingContractError(
        "Marketing 已响应，但返回规则与当前创建命令的完整载荷不一致。",
      );
    }
  }

  function validateBenefit(
    command: PendingMarketingCommand,
    value: Benefit,
  ) {
    if (
      value.userId !== stringPayload(command.payload, "userId")
      || value.ruleCode !== stringPayload(command.payload, "ruleCode")
      || !value.benefitNo
    ) {
      throw new MarketingContractError(
        "Marketing 已响应，但返回权益与当前顾客或规则不一致。",
      );
    }
  }

  async function callCommand(
    api: MarketingApi,
    command: PendingMarketingCommand,
  ): Promise<MarketingRule | Benefit> {
    if (command.kind === "rule") {
      return api.createRule(ruleInput(command));
    }
    return api.grantBenefit(
      stringPayload(command.payload, "userId"),
      stringPayload(command.payload, "ruleCode"),
      command.commandKey,
    );
  }

  function resetAcceptedForm(
    command: PendingMarketingCommand,
    value: MarketingRule | Benefit,
  ) {
    if (command.kind === "rule" && "version" in value) {
      Object.assign(rule, defaultRuleForm());
      grant.ruleCode = value.ruleCode;
      return;
    }
    grant.userId = "";
    grant.grantKey = newGrantKey();
  }

  async function executePending(
    access: ActiveAccess,
    command: PendingMarketingCommand,
  ): Promise<MarketingRule | Benefit | null> {
    const requestRevision = ++commandRevision;
    submitting.value = true;
    commandPhase.value = "processing";
    commandMessage.value =
      `${pendingCommandLabel.value}正在等待 Marketing 确认。`;
    try {
      const value = await callCommand(access.api, command);
      requireCurrent(access);
      if (requestRevision !== commandRevision) {
        return null;
      }
      if (command.kind === "rule") {
        const marketingRule = value as MarketingRule;
        validateRule(command, marketingRule);
        createdRule.value = marketingRule;
        grantedBenefit.value = null;
        resetAcceptedForm(command, marketingRule);
        settleAccepted(
          access,
          `Marketing 已确认规则 ${marketingRule.ruleCode} 创建成功。`,
        );
      } else {
        const benefit = value as Benefit;
        validateBenefit(command, benefit);
        grantedBenefit.value = benefit;
        resetAcceptedForm(command, benefit);
        settleAccepted(
          access,
          `Marketing 已确认权益 ${benefit.benefitNo} 已发放，当前状态为 ${benefit.status}。`,
        );
      }
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== commandRevision) {
        return null;
      }
      if (resultMayBeUnknown(cause)) {
        commandPhase.value = "unknown";
        commandMessage.value = command.kind === "rule"
          ? `${errorMessage(cause, "规则创建响应未能确认。")} `
            + "页面没有显示成功；当前 API 没有管理端规则查询，不能通过重读或重复 POST 归因原命令。"
          : `${errorMessage(cause, "权益发放响应未能确认。")} `
            + "页面没有显示成功；顾客、规则和原 grantKey 已保留，只能原样重试。";
        return null;
      }
      settleRejected(
        access,
        errorMessage(cause, "Marketing 已明确拒绝当前命令。"),
      );
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === commandRevision) {
        submitting.value = false;
      }
    }
  }

  function runPending(
    access: ActiveAccess,
    command: PendingMarketingCommand,
  ): Promise<MarketingRule | Benefit | null> {
    if (activeCommandPromise) {
      return activeCommandPromise;
    }
    beginPending(access, command);
    const request = executePending(access, command);
    activeCommandPromise = request;
    const clear = () => {
      if (activeCommandPromise === request) {
        activeCommandPromise = null;
      }
    };
    void request.then(clear, clear);
    return request;
  }

  function createRule(
    context: AdminMarketingAccessContext,
  ): Promise<MarketingRule | Benefit | null> {
    const access = requireAccess(
      context,
      "当前会话无权创建营销规则。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条营销命令尚未确认，不能创建第二条命令。";
      return Promise.resolve(null);
    }
    const regions: RegionRestriction[] =
      rule.regionLevel && normalizedInput(rule.regionCode)
        ? [{
          level: rule.regionLevel,
          regionCode: normalizedInput(rule.regionCode),
        }]
        : [];
    const payload: CreateMarketingRuleInput = {
      ruleCode: normalizedInput(rule.ruleCode),
      name: normalizedInput(rule.name),
      benefitType: rule.benefitType,
      thresholdAmount: normalizedInput(rule.thresholdAmount),
      discountAmount: normalizedInput(rule.discountAmount),
      stackOrder: Number(rule.stackOrder),
      validFrom: new Date(rule.validFrom).toISOString(),
      validUntil: new Date(rule.validUntil).toISOString(),
      regions,
    };
    return runPending(access, {
      kind: "rule",
      referenceNo: payload.ruleCode,
      commandKey: payload.ruleCode,
      payload: { ...payload },
      createdAt: new Date().toISOString(),
    });
  }

  function grantBenefit(
    context: AdminMarketingAccessContext,
  ): Promise<MarketingRule | Benefit | null> {
    const access = requireAccess(
      context,
      "当前会话无权发放营销权益。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条营销命令尚未确认，不能生成第二个发放身份。";
      return Promise.resolve(null);
    }
    const userId = normalizedInput(grant.userId);
    const ruleCode = normalizedInput(grant.ruleCode);
    const grantKey = normalizedInput(grant.grantKey);
    return runPending(access, {
      kind: "grant",
      referenceNo: `${userId} / ${ruleCode}`,
      commandKey: grantKey,
      payload: { userId, ruleCode, grantKey },
      createdAt: new Date().toISOString(),
    });
  }

  function retryPending(
    context: AdminMarketingAccessContext,
  ): Promise<MarketingRule | Benefit | null> {
    const access = requireAccess(
      context,
      "当前会话无权重试营销命令。",
    );
    const command = pendingCommand.value;
    if (!access || !command) {
      if (!command) {
        commandPhase.value = "rejected";
        commandMessage.value = "当前没有可重试的结果未知命令。";
      }
      return Promise.resolve(null);
    }
    if (command.kind === "rule") {
      commandPhase.value = "unknown";
      commandMessage.value =
        "规则创建接口没有稳定幂等命令键，也没有管理端规则查询；不能盲目重复 POST。";
      return Promise.resolve(null);
    }
    if (activeCommandPromise) {
      return activeCommandPromise;
    }
    const request = executePending(access, command);
    activeCommandPromise = request;
    const clear = () => {
      if (activeCommandPromise === request) {
        activeCommandPromise = null;
      }
    };
    void request.then(clear, clear);
    return request;
  }

  function resetCommandNotice() {
    if (pendingCommand.value) {
      return;
    }
    commandPhase.value = "idle";
    commandMessage.value = null;
  }

  return {
    rule,
    grant,
    createdRule,
    grantedBenefit,
    commandPhase,
    commandMessage,
    pendingCommand,
    pendingCommandLabel,
    pendingReferenceNo,
    submitting,
    commandBlocked,
    canRetryPending,
    synchronizeAccess,
    createRule,
    grantBenefit,
    retryPending,
    resetCommandNotice,
  };
});
