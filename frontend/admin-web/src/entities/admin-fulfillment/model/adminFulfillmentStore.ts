import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createFulfillmentApi,
  type AddLogisticsTraceInput,
  type BusinessId,
  type Fulfillment,
  type FulfillmentApi,
  type NearbyShipmentPosition,
  type ReturnReceipt,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_STORAGE_PREFIX =
  "plain-journal:admin-fulfillment:pending-command:v1:";

export type FulfillmentCommandPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

type FulfillmentCommandKind =
  | "picking"
  | "packed"
  | "ship"
  | "trace"
  | "exception"
  | "resolve"
  | "receive"
  | "inspect";

export interface AdminFulfillmentAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: FulfillmentApi;
}

interface PendingFulfillmentCommand {
  kind: FulfillmentCommandKind;
  referenceNo: string;
  commandKey: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

interface ShipForm {
  carrier: string;
  trackingNo: string;
}

type NumericInput = string | number;

interface TraceForm {
  externalEventId: string;
  nodeType: "TRANSIT" | "DELIVERING" | "SIGNED" | "EXCEPTION";
  description: string;
  locationName: string;
  longitude: NumericInput;
  latitude: NumericInput;
}

interface ReasonForm {
  reason: string;
}

interface ResolutionForm extends ReasonForm {
  commandId: string;
}

export class FulfillmentAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的履约请求结果不会写入当前页面。");
    this.name = "FulfillmentAccessChangedError";
  }
}

export class FulfillmentContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FulfillmentContractError";
  }
}

function isActiveContext(
  context: AdminFulfillmentAccessContext,
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

function createApi(accessToken: string): FulfillmentApi {
  return createFulfillmentApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function newIdentity(prefix: string): string {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}:${suffix}`;
}

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_STORAGE_PREFIX}${operatorId}`;
}

function parsePending(raw: string | null): PendingFulfillmentCommand | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("kind" in value)
      || !("referenceNo" in value)
      || !("commandKey" in value)
      || !("payload" in value)
      || !("createdAt" in value)
      || ![
        "picking",
        "packed",
        "ship",
        "trace",
        "exception",
        "resolve",
        "receive",
        "inspect",
      ].includes(String(value.kind))
      || typeof value.referenceNo !== "string"
      || value.referenceNo.length === 0
      || typeof value.commandKey !== "string"
      || value.commandKey.length === 0
      || !value.payload
      || typeof value.payload !== "object"
      || Array.isArray(value.payload)
      || typeof value.createdAt !== "string"
    ) {
      return null;
    }
    return value as PendingFulfillmentCommand;
  } catch {
    return null;
  }
}

function loadPending(operatorId: BusinessId): PendingFulfillmentCommand | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(localStorage.getItem(storageKey(operatorId)));
}

function savePending(
  operatorId: BusinessId,
  value: PendingFulfillmentCommand | null,
) {
  if (typeof localStorage === "undefined") {
    return;
  }
  if (value) {
    localStorage.setItem(storageKey(operatorId), JSON.stringify(value));
  } else {
    localStorage.removeItem(storageKey(operatorId));
  }
}

function resultMayBeUnknown(cause: unknown): boolean {
  if (cause instanceof FulfillmentContractError) {
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

function normalizedInput(value: unknown): string {
  return String(value ?? "").trim();
}

function tracePayload(
  payload: Record<string, unknown>,
): AddLogisticsTraceInput {
  return {
    externalEventId: stringPayload(payload, "externalEventId"),
    nodeType: stringPayload(payload, "nodeType") as AddLogisticsTraceInput["nodeType"],
    description: stringPayload(payload, "description"),
    locationName: stringPayload(payload, "locationName") || null,
    longitude: stringPayload(payload, "longitude") || null,
    latitude: stringPayload(payload, "latitude") || null,
    occurredAt: stringPayload(payload, "occurredAt"),
  };
}

export const useAdminFulfillmentStore = defineStore(
  "admin-fulfillment",
  () => {
    const fulfillments = ref<Fulfillment[]>([]);
    const returns = ref<ReturnReceipt[]>([]);
    const fulfillmentStatus = ref("");
    const returnStatus = ref("");
    const loading = ref(false);
    const loadError = ref<string | null>(null);
    const nearbyPositions = ref<NearbyShipmentPosition[]>([]);
    const geoQuery = reactive({
      longitude: "120.155100" as NumericInput,
      latitude: "30.274100" as NumericInput,
      radiusMeters: "50000" as NumericInput,
      limit: "20" as NumericInput,
    });
    const geoBusy = ref(false);
    const geoMessage = ref<string | null>(null);
    const geoError = ref<string | null>(null);
    const shipForms = reactive<Record<string, ShipForm>>({});
    const traceForms = reactive<Record<string, TraceForm>>({});
    const exceptionForms = reactive<Record<string, ReasonForm>>({});
    const resolutionForms = reactive<Record<string, ResolutionForm>>({});
    const inspectRemarks = reactive<Record<string, string>>({});
    const commandPhase = ref<FulfillmentCommandPhase>("idle");
    const commandMessage = ref<string | null>(null);
    const pendingCommand = ref<PendingFulfillmentCommand | null>(null);
    const submitting = ref(false);
    const activeOperatorId = ref<BusinessId | null>(null);
    let activeAccessToken: string | null = null;
    let accessRevision = 0;
    let factsRevision = 0;
    let geoRevision = 0;
    let commandRevision = 0;
    let activeCommandPromise: Promise<Fulfillment | ReturnReceipt | null> | null = null;

    const commandBlocked = computed(() =>
      submitting.value
      || (commandPhase.value === "unknown" && Boolean(pendingCommand.value)));
    const pendingReferenceNo = computed(() =>
      pendingCommand.value?.referenceNo ?? null);
    const pendingCommandLabel = computed(() => {
      const kind = pendingCommand.value?.kind;
      return kind ? {
        picking: "开始拣货",
        packed: "确认打包",
        ship: "确认发货",
        trace: "追加物流轨迹",
        exception: "标记履约异常",
        resolve: "恢复履约异常",
        receive: "确认退货收货",
        inspect: "确认退货验收",
      }[kind] : null;
    });

    function activeAccess(
      context: AdminFulfillmentAccessContext,
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
        throw new FulfillmentAccessChangedError();
      }
    }

    function hydratePending(command: PendingFulfillmentCommand | null) {
      if (!command) {
        return;
      }
      const { referenceNo, payload } = command;
      if (command.kind === "ship") {
        shipForms[referenceNo] = {
          carrier: stringPayload(payload, "carrier"),
          trackingNo: stringPayload(payload, "trackingNo"),
        };
      } else if (command.kind === "trace") {
        traceForms[referenceNo] = {
          externalEventId: stringPayload(payload, "externalEventId"),
          nodeType: stringPayload(payload, "nodeType") as TraceForm["nodeType"],
          description: stringPayload(payload, "description"),
          locationName: stringPayload(payload, "locationName"),
          longitude: stringPayload(payload, "longitude"),
          latitude: stringPayload(payload, "latitude"),
        };
      } else if (command.kind === "exception") {
        exceptionForms[referenceNo] = {
          reason: stringPayload(payload, "reason"),
        };
      } else if (command.kind === "resolve") {
        resolutionForms[referenceNo] = {
          commandId: command.commandKey,
          reason: stringPayload(payload, "reason"),
        };
      } else if (command.kind === "inspect") {
        inspectRemarks[referenceNo] = stringPayload(payload, "remark");
      }
    }

    function synchronizeAccess(context: AdminFulfillmentAccessContext) {
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
      factsRevision += 1;
      geoRevision += 1;
      commandRevision += 1;
      loading.value = false;
      geoBusy.value = false;
      submitting.value = false;
      activeCommandPromise = null;

      if (operatorChanged) {
        fulfillments.value = [];
        returns.value = [];
        nearbyPositions.value = [];
        loadError.value = null;
        geoMessage.value = null;
        geoError.value = null;
        for (const forms of [
          shipForms,
          traceForms,
          exceptionForms,
          resolutionForms,
          inspectRemarks,
        ]) {
          for (const key of Object.keys(forms)) {
            delete forms[key];
          }
        }
        pendingCommand.value = nextOperatorId
          ? loadPending(nextOperatorId)
          : null;
        hydratePending(pendingCommand.value);
        commandPhase.value = pendingCommand.value ? "unknown" : "idle";
        commandMessage.value = pendingCommand.value
          ? "发现一条尚未确认的履约命令。业务号、命令身份和原始载荷已恢复；请读取权威事实或原样重试。"
          : null;
      } else if (pendingCommand.value) {
        commandPhase.value = "unknown";
        commandMessage.value =
          "员工会话凭据已更新，原履约命令继续保持结果未知。";
      }
      return activeAccess(context);
    }

    function requireAccess(
      context: AdminFulfillmentAccessContext,
      message: string,
    ): ActiveAccess | null {
      const access = synchronizeAccess(context);
      if (!access) {
        commandPhase.value = "rejected";
        commandMessage.value = message;
      }
      return access;
    }

    function upsertFulfillment(value: Fulfillment) {
      const index = fulfillments.value.findIndex((item) =>
        item.fulfillmentNo === value.fulfillmentNo);
      if (index >= 0) {
        fulfillments.value[index] = value;
      } else {
        fulfillments.value.unshift(value);
      }
    }

    function upsertReturn(value: ReturnReceipt) {
      const index = returns.value.findIndex((item) =>
        item.returnReceiptNo === value.returnReceiptNo);
      if (index >= 0) {
        returns.value[index] = value;
      } else {
        returns.value.unshift(value);
      }
    }

    async function loadFacts(
      context: AdminFulfillmentAccessContext,
    ): Promise<void> {
      const access = synchronizeAccess(context);
      if (!access) {
        loadError.value = "当前会话无权读取履约工作区。";
        return;
      }
      const requestRevision = ++factsRevision;
      loading.value = true;
      loadError.value = null;
      try {
        const [orders, returnReceipts] = await Promise.all([
          access.api.adminFulfillments(fulfillmentStatus.value || undefined),
          access.api.adminReturns(returnStatus.value || undefined),
        ]);
        requireCurrent(access);
        if (requestRevision !== factsRevision) {
          return;
        }
        fulfillments.value = orders;
        returns.value = returnReceipts;
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          return;
        }
        if (requestRevision === factsRevision) {
          loadError.value = errorMessage(
            cause,
            "履约与退货事实暂时无法读取。",
          );
        }
      } finally {
        if (accessIsCurrent(access) && requestRevision === factsRevision) {
          loading.value = false;
        }
      }
    }

    function shipForm(referenceNo: string): ShipForm {
      shipForms[referenceNo] ??= { carrier: "", trackingNo: "" };
      return shipForms[referenceNo];
    }

    function traceForm(referenceNo: string): TraceForm {
      traceForms[referenceNo] ??= {
        externalEventId: newIdentity("trace"),
        nodeType: "TRANSIT",
        description: "",
        locationName: "",
        longitude: "",
        latitude: "",
      };
      return traceForms[referenceNo];
    }

    function exceptionForm(referenceNo: string): ReasonForm {
      exceptionForms[referenceNo] ??= { reason: "" };
      return exceptionForms[referenceNo];
    }

    function resolutionForm(referenceNo: string): ResolutionForm {
      resolutionForms[referenceNo] ??= {
        commandId: newIdentity("fulfillment-exception"),
        reason: "",
      };
      return resolutionForms[referenceNo];
    }

    function beginPending(
      access: ActiveAccess,
      command: PendingFulfillmentCommand,
    ) {
      pendingCommand.value = command;
      savePending(access.operatorId, command);
      commandPhase.value = "processing";
      commandMessage.value = `${pendingCommandLabel.value}命令正在等待 Fulfillment 确认。`;
    }

    function settleAccepted(
      access: ActiveAccess,
      message: string,
    ) {
      pendingCommand.value = null;
      savePending(access.operatorId, null);
      commandPhase.value = "accepted";
      commandMessage.value = message;
    }

    function settleRejected(
      access: ActiveAccess,
      message: string,
    ) {
      pendingCommand.value = null;
      savePending(access.operatorId, null);
      commandPhase.value = "rejected";
      commandMessage.value = message;
    }

    function validateResult(
      command: PendingFulfillmentCommand,
      value: Fulfillment | ReturnReceipt,
    ) {
      const actual = "fulfillmentNo" in value
        ? value.fulfillmentNo
        : value.returnReceiptNo;
      if (actual !== command.referenceNo) {
        throw new FulfillmentContractError(
          "Fulfillment 已响应，但返回事实与当前命令业务号不一致。",
        );
      }
    }

    async function callCommand(
      api: FulfillmentApi,
      command: PendingFulfillmentCommand,
    ): Promise<Fulfillment | ReturnReceipt> {
      const { kind, referenceNo, payload } = command;
      switch (kind) {
        case "picking":
          return api.startPicking(referenceNo);
        case "packed":
          return api.markPacked(referenceNo);
        case "ship":
          return api.shipFulfillment(referenceNo, {
            carrier: stringPayload(payload, "carrier"),
            trackingNo: stringPayload(payload, "trackingNo"),
          });
        case "trace":
          return api.addLogisticsTrace(referenceNo, tracePayload(payload));
        case "exception":
          return api.markFulfillmentException(
            referenceNo,
            stringPayload(payload, "reason"),
          );
        case "resolve":
          return api.resolveFulfillmentException(
            referenceNo,
            command.commandKey,
            stringPayload(payload, "reason"),
          );
        case "receive":
          return api.receiveReturn(referenceNo);
        case "inspect":
          return api.inspectReturn(
            referenceNo,
            stringPayload(payload, "remark"),
          );
      }
    }

    function resetAcceptedForm(command: PendingFulfillmentCommand) {
      if (command.kind === "trace") {
        traceForms[command.referenceNo] = {
          externalEventId: newIdentity("trace"),
          nodeType: "TRANSIT",
          description: "",
          locationName: "",
          longitude: "",
          latitude: "",
        };
      } else if (command.kind === "exception") {
        exceptionForms[command.referenceNo] = { reason: "" };
      } else if (command.kind === "resolve") {
        resolutionForms[command.referenceNo] = {
          commandId: newIdentity("fulfillment-exception"),
          reason: "",
        };
      } else if (command.kind === "inspect") {
        inspectRemarks[command.referenceNo] = "";
      }
    }

    async function executePending(
      access: ActiveAccess,
      command: PendingFulfillmentCommand,
    ): Promise<Fulfillment | ReturnReceipt | null> {
      const requestRevision = ++commandRevision;
      submitting.value = true;
      commandPhase.value = "processing";
      commandMessage.value = `${pendingCommandLabel.value}命令正在等待 Fulfillment 确认。`;
      try {
        const value = await callCommand(access.api, command);
        requireCurrent(access);
        if (requestRevision !== commandRevision) {
          return null;
        }
        validateResult(command, value);
        if ("fulfillmentNo" in value) {
          upsertFulfillment(value);
        } else {
          upsertReturn(value);
        }
        resetAcceptedForm(command);
        settleAccepted(
          access,
          `Fulfillment 已确认 ${command.referenceNo} 当前状态为 ${value.status}。`,
        );
        return value;
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          return null;
        }
        if (requestRevision !== commandRevision) {
          return null;
        }
        if (resultMayBeUnknown(cause)) {
          commandPhase.value = "unknown";
          commandMessage.value =
            `${errorMessage(cause, "履约命令响应未能确认。")} `
            + "页面没有把命令显示为成功；业务号、命令身份和原始载荷已保留。";
          return null;
        }
        settleRejected(
          access,
          errorMessage(cause, "Fulfillment 已明确拒绝当前命令。"),
        );
        return null;
      } finally {
        if (accessIsCurrent(access) && requestRevision === commandRevision) {
          submitting.value = false;
        }
      }
    }

    function submit(
      context: AdminFulfillmentAccessContext,
      command: PendingFulfillmentCommand,
    ): Promise<Fulfillment | ReturnReceipt | null> {
      const access = requireAccess(
        context,
        "当前会话无权提交 Fulfillment 命令。",
      );
      if (!access) {
        return Promise.resolve(null);
      }
      if (pendingCommand.value) {
        commandPhase.value = "unknown";
        commandMessage.value =
          "已有一条结果未知命令，不能生成或提交第二条命令；请先读取权威事实或原样重试。";
        return Promise.resolve(null);
      }
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

    function naturalCommand(
      kind: FulfillmentCommandKind,
      referenceNo: string,
      payload: Record<string, unknown> = {},
    ): PendingFulfillmentCommand {
      return {
        kind,
        referenceNo,
        commandKey: `${kind}:${referenceNo}`,
        payload,
        createdAt: new Date().toISOString(),
      };
    }

    function startPicking(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      return submit(context, naturalCommand("picking", fulfillmentNo));
    }

    function markPacked(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      return submit(context, naturalCommand("packed", fulfillmentNo));
    }

    function ship(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      const form = shipForm(fulfillmentNo);
      const carrier = normalizedInput(form.carrier).toUpperCase();
      const trackingNo = normalizedInput(form.trackingNo);
      if (!carrier || !trackingNo) {
        commandPhase.value = "rejected";
        commandMessage.value = "承运商和运单号不能为空。";
        return Promise.resolve(null);
      }
      return submit(context, naturalCommand("ship", fulfillmentNo, {
        carrier,
        trackingNo,
      }));
    }

    function addTrace(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      const form = traceForm(fulfillmentNo);
      const longitude = normalizedInput(form.longitude);
      const latitude = normalizedInput(form.latitude);
      if (Boolean(longitude) !== Boolean(latitude)) {
        commandPhase.value = "rejected";
        commandMessage.value = "经度与纬度必须同时填写，不能只提交一个坐标。";
        return Promise.resolve(null);
      }
      if (!normalizedInput(form.description)) {
        commandPhase.value = "rejected";
        commandMessage.value = "物流轨迹说明不能为空。";
        return Promise.resolve(null);
      }
      const occurredAt = new Date().toISOString();
      return submit(context, {
        kind: "trace",
        referenceNo: fulfillmentNo,
        commandKey: form.externalEventId,
        payload: {
          externalEventId: form.externalEventId,
          nodeType: form.nodeType,
          description: normalizedInput(form.description),
          locationName: normalizedInput(form.locationName),
          longitude,
          latitude,
          occurredAt,
        },
        createdAt: occurredAt,
      });
    }

    function markException(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      const reason = normalizedInput(exceptionForm(fulfillmentNo).reason);
      if (!reason) {
        commandPhase.value = "rejected";
        commandMessage.value = "履约异常原因不能为空。";
        return Promise.resolve(null);
      }
      return submit(context, naturalCommand("exception", fulfillmentNo, {
        reason,
      }));
    }

    function resolveException(
      context: AdminFulfillmentAccessContext,
      fulfillmentNo: string,
    ) {
      const form = resolutionForm(fulfillmentNo);
      const reason = normalizedInput(form.reason);
      if (!reason) {
        commandPhase.value = "rejected";
        commandMessage.value = "异常恢复原因不能为空。";
        return Promise.resolve(null);
      }
      return submit(context, {
        kind: "resolve",
        referenceNo: fulfillmentNo,
        commandKey: form.commandId,
        payload: { reason },
        createdAt: new Date().toISOString(),
      });
    }

    function receiveReturn(
      context: AdminFulfillmentAccessContext,
      returnReceiptNo: string,
    ) {
      return submit(context, naturalCommand("receive", returnReceiptNo));
    }

    function inspectReturn(
      context: AdminFulfillmentAccessContext,
      returnReceiptNo: string,
    ) {
      const remark = normalizedInput(inspectRemarks[returnReceiptNo]);
      if (!remark) {
        commandPhase.value = "rejected";
        commandMessage.value = "退货验收说明不能为空。";
        return Promise.resolve(null);
      }
      return submit(context, naturalCommand("inspect", returnReceiptNo, {
        remark,
      }));
    }

    function retryPending(
      context: AdminFulfillmentAccessContext,
    ): Promise<Fulfillment | ReturnReceipt | null> {
      const access = requireAccess(
        context,
        "当前会话无权重试 Fulfillment 命令。",
      );
      if (!access || !pendingCommand.value) {
        if (!pendingCommand.value) {
          commandPhase.value = "rejected";
          commandMessage.value = "当前没有可重试的结果未知命令。";
        }
        return Promise.resolve(null);
      }
      if (activeCommandPromise) {
        return activeCommandPromise;
      }
      const command = pendingCommand.value;
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

    function historyMatches(
      value: Fulfillment,
      expectedCommand: string,
      expectedReason?: string,
    ): boolean {
      return value.history.some((history) =>
        history.command === expectedCommand
        && history.operatorId === activeOperatorId.value
        && (
          expectedReason === undefined
          || history.reason === expectedReason
        ));
    }

    function authorityConfirms(
      command: PendingFulfillmentCommand,
      value: Fulfillment | ReturnReceipt,
    ): boolean {
      const payload = command.payload;
      if ("fulfillmentNo" in value) {
        switch (command.kind) {
          case "picking":
            return historyMatches(value, "START_PICKING");
          case "packed":
            return historyMatches(value, "MARK_PACKED");
          case "ship":
            return value.carrier === stringPayload(payload, "carrier")
              && value.trackingNo === stringPayload(payload, "trackingNo")
              && historyMatches(value, "SHIP");
          case "trace":
            return value.traces.some((trace) =>
              trace.externalEventId === command.commandKey
              && trace.nodeType === stringPayload(payload, "nodeType")
              && trace.description === stringPayload(payload, "description")
              && (trace.locationName ?? "") === stringPayload(payload, "locationName")
              && String(trace.longitude ?? "") === stringPayload(payload, "longitude")
              && String(trace.latitude ?? "") === stringPayload(payload, "latitude"));
          case "exception":
            return value.status === "EXCEPTION"
              && historyMatches(
                value,
                "MARK_EXCEPTION",
                stringPayload(payload, "reason"),
              );
          case "resolve":
            return false;
          default:
            return false;
        }
      }
      if (command.kind === "receive") {
        return ["RECEIVED", "INSPECTED"].includes(value.status);
      }
      if (command.kind === "inspect") {
        return value.status === "INSPECTED"
          && value.inspectionRemark === stringPayload(payload, "remark");
      }
      return false;
    }

    async function readPendingAuthority(
      context: AdminFulfillmentAccessContext,
    ): Promise<Fulfillment | ReturnReceipt | null> {
      const access = requireAccess(
        context,
        "当前会话无权读取 Fulfillment 权威事实。",
      );
      const command = pendingCommand.value;
      if (!access || !command) {
        if (!command) {
          commandPhase.value = "rejected";
          commandMessage.value = "当前没有待核对的结果未知命令。";
        }
        return null;
      }
      const requestRevision = ++commandRevision;
      submitting.value = true;
      try {
        const value = ["receive", "inspect"].includes(command.kind)
          ? await access.api.adminReturn(command.referenceNo)
          : await access.api.adminFulfillment(command.referenceNo);
        requireCurrent(access);
        if (requestRevision !== commandRevision) {
          return null;
        }
        validateResult(command, value);
        if ("fulfillmentNo" in value) {
          upsertFulfillment(value);
        } else {
          upsertReturn(value);
        }
        if (authorityConfirms(command, value)) {
          resetAcceptedForm(command);
          settleAccepted(
            access,
            `Fulfillment 权威事实已确认原命令，${command.referenceNo} 当前为 ${value.status}。`,
          );
        } else {
          commandPhase.value = "unknown";
          commandMessage.value = command.kind === "resolve"
            ? "履约事实可以显示当前状态，但响应模型不公开异常恢复命令 ID；无法把当前状态归因于原命令，必须沿用原 ID 重试确认。"
            : `当前权威事实尚未证明原命令已生效，${command.referenceNo} 仍为 ${value.status}；不能生成第二条命令。`;
        }
        return value;
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          return null;
        }
        if (requestRevision === commandRevision) {
          commandPhase.value = "unknown";
          commandMessage.value =
            `${errorMessage(cause, "Fulfillment 权威事实暂时无法读取。")} `
            + "原命令继续保持结果未知。";
        }
        return null;
      } finally {
        if (accessIsCurrent(access) && requestRevision === commandRevision) {
          submitting.value = false;
        }
      }
    }

    async function searchNearby(
      context: AdminFulfillmentAccessContext,
    ): Promise<void> {
      const access = synchronizeAccess(context);
      if (!access) {
        geoError.value = "当前会话无权读取附近物流位置。";
        return;
      }
      const requestRevision = ++geoRevision;
      geoBusy.value = true;
      geoError.value = null;
      geoMessage.value = null;
      try {
        const values = await access.api.nearbyShipmentPositions({
          longitude: normalizedInput(geoQuery.longitude),
          latitude: normalizedInput(geoQuery.latitude),
          radiusMeters: Number(geoQuery.radiusMeters),
          limit: Number(geoQuery.limit),
        });
        requireCurrent(access);
        if (requestRevision !== geoRevision) {
          return;
        }
        nearbyPositions.value = values;
        geoMessage.value =
          `Fulfillment 返回 ${values.length} 个范围内最新位置。`;
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          return;
        }
        if (requestRevision === geoRevision) {
          geoError.value = errorMessage(
            cause,
            "附近物流位置查询未完成。",
          );
        }
      } finally {
        if (accessIsCurrent(access) && requestRevision === geoRevision) {
          geoBusy.value = false;
        }
      }
    }

    async function rebuildGeoCache(
      context: AdminFulfillmentAccessContext,
    ): Promise<void> {
      const access = synchronizeAccess(context);
      if (!access) {
        geoError.value = "当前会话无权重建物流位置缓存。";
        return;
      }
      const requestRevision = ++geoRevision;
      geoBusy.value = true;
      geoError.value = null;
      geoMessage.value = null;
      try {
        const result = await access.api.rebuildShipmentGeoCache();
        requireCurrent(access);
        if (requestRevision !== geoRevision) {
          return;
        }
        geoMessage.value =
          `Redis GEO 已从 MySQL 投影重建 ${result.cached}/${result.scanned} 条位置。`;
      } catch (cause) {
        if (!accessIsCurrent(access)) {
          return;
        }
        if (requestRevision === geoRevision) {
          geoError.value =
            `${errorMessage(cause, "Redis GEO 重建未完成。")} `
            + "MySQL 物流轨迹未被修改。";
        }
      } finally {
        if (accessIsCurrent(access) && requestRevision === geoRevision) {
          geoBusy.value = false;
        }
      }
    }

    function resetCommandNotice() {
      if (pendingCommand.value) {
        return;
      }
      commandPhase.value = "idle";
      commandMessage.value = null;
    }

    return {
      fulfillments,
      returns,
      fulfillmentStatus,
      returnStatus,
      loading,
      loadError,
      nearbyPositions,
      geoQuery,
      geoBusy,
      geoMessage,
      geoError,
      inspectRemarks,
      commandPhase,
      commandMessage,
      pendingCommand,
      pendingReferenceNo,
      pendingCommandLabel,
      submitting,
      commandBlocked,
      synchronizeAccess,
      loadFacts,
      shipForm,
      traceForm,
      exceptionForm,
      resolutionForm,
      startPicking,
      markPacked,
      ship,
      addTrace,
      markException,
      resolveException,
      receiveReturn,
      inspectReturn,
      retryPending,
      readPendingAuthority,
      searchNearby,
      rebuildGeoCache,
      resetCommandNotice,
    };
  },
);
