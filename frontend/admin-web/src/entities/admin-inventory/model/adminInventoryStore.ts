import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createInventoryApi,
  type AdjustStockInput,
  type BusinessId,
  type InventoryApi,
  type StockPosition,
  type Warehouse,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_STORAGE_PREFIX =
  "plain-journal:admin-inventory:pending-command:v1:";

type NumericInput = string | number;
type InventoryCommandKind = "warehouse" | "adjustment";

export type InventoryCommandPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

export interface AdminInventoryAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: InventoryApi;
}

interface PendingInventoryCommand {
  kind: InventoryCommandKind;
  referenceNo: string;
  commandKey: string;
  payload: Record<string, unknown>;
  createdAt: string;
}

export class InventoryAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的库存请求结果不会写入当前页面。");
    this.name = "InventoryAccessChangedError";
  }
}

export class InventoryContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InventoryContractError";
  }
}

function isActiveContext(
  context: AdminInventoryAccessContext,
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

function createApi(accessToken: string): InventoryApi {
  return createInventoryApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function newMovementNo(): string {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `admin-stock:${suffix}`;
}

function normalizedInput(value: unknown): string {
  return String(value ?? "").trim();
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

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_STORAGE_PREFIX}${operatorId}`;
}

function parsePending(raw: string | null): PendingInventoryCommand | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (
      !value
      || typeof value !== "object"
      || !("kind" in value)
      || !["warehouse", "adjustment"].includes(String(value.kind))
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
    return value as PendingInventoryCommand;
  } catch {
    return null;
  }
}

function loadPending(operatorId: BusinessId): PendingInventoryCommand | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(localStorage.getItem(storageKey(operatorId)));
}

function savePending(
  operatorId: BusinessId,
  value: PendingInventoryCommand | null,
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
  if (cause instanceof InventoryContractError) {
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

export const useAdminInventoryStore = defineStore("admin-inventory", () => {
  const warehouses = ref<Warehouse[]>([]);
  const stock = ref<StockPosition | null>(null);
  const warehouseForm = reactive({ code: "", name: "" });
  const lookup = reactive({ warehouseId: "", skuId: "" });
  const adjustment = reactive({
    movementNo: newMovementNo(),
    warehouseId: "",
    skuId: "",
    quantityDelta: 0 as NumericInput,
    reason: "",
  });
  const loadingWarehouses = ref(false);
  const stockBusy = ref(false);
  const loadError = ref<string | null>(null);
  const stockError = ref<string | null>(null);
  const commandPhase = ref<InventoryCommandPhase>("idle");
  const commandMessage = ref<string | null>(null);
  const pendingCommand = ref<PendingInventoryCommand | null>(null);
  const submitting = ref(false);
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let warehouseRevision = 0;
  let stockRevision = 0;
  let commandRevision = 0;
  let activeCommandPromise: Promise<Warehouse | StockPosition | null> | null = null;

  const pendingCommandLabel = computed(() => {
    if (pendingCommand.value?.kind === "warehouse") {
      return "创建仓库";
    }
    if (pendingCommand.value?.kind === "adjustment") {
      return "调整库存";
    }
    return null;
  });
  const pendingReferenceNo = computed(() =>
    pendingCommand.value?.referenceNo ?? null);
  const commandBlocked = computed(() =>
    submitting.value
    || (commandPhase.value === "unknown" && Boolean(pendingCommand.value)));

  function activeAccess(
    context: AdminInventoryAccessContext,
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
      throw new InventoryAccessChangedError();
    }
  }

  function hydratePending(command: PendingInventoryCommand | null) {
    if (!command) {
      return;
    }
    if (command.kind === "warehouse") {
      warehouseForm.code = stringPayload(command.payload, "code");
      warehouseForm.name = stringPayload(command.payload, "name");
      return;
    }
    adjustment.movementNo = command.commandKey;
    adjustment.warehouseId = stringPayload(command.payload, "warehouseId");
    adjustment.skuId = stringPayload(command.payload, "skuId");
    adjustment.quantityDelta = numberPayload(command.payload, "quantityDelta");
    adjustment.reason = stringPayload(command.payload, "reason");
  }

  function synchronizeAccess(context: AdminInventoryAccessContext) {
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
    warehouseRevision += 1;
    stockRevision += 1;
    commandRevision += 1;
    loadingWarehouses.value = false;
    stockBusy.value = false;
    submitting.value = false;
    activeCommandPromise = null;

    if (operatorChanged) {
      warehouses.value = [];
      stock.value = null;
      loadError.value = null;
      stockError.value = null;
      warehouseForm.code = "";
      warehouseForm.name = "";
      lookup.warehouseId = "";
      lookup.skuId = "";
      adjustment.movementNo = newMovementNo();
      adjustment.warehouseId = "";
      adjustment.skuId = "";
      adjustment.quantityDelta = 0;
      adjustment.reason = "";
      pendingCommand.value = nextOperatorId
        ? loadPending(nextOperatorId)
        : null;
      hydratePending(pendingCommand.value);
      commandPhase.value = pendingCommand.value ? "unknown" : "idle";
      commandMessage.value = pendingCommand.value
        ? "发现一条尚未确认的库存命令。业务号和原始载荷已恢复；请读取权威事实或沿用原流水重试。"
        : null;
    } else if (pendingCommand.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "员工会话凭据已更新，原库存命令继续保持结果未知。";
    }
    return activeAccess(context);
  }

  function requireAccess(
    context: AdminInventoryAccessContext,
    message: string,
  ): ActiveAccess | null {
    const access = synchronizeAccess(context);
    if (!access) {
      commandPhase.value = "rejected";
      commandMessage.value = message;
    }
    return access;
  }

  function upsertWarehouse(value: Warehouse) {
    const index = warehouses.value.findIndex((warehouse) =>
      warehouse.id === value.id || warehouse.code === value.code);
    if (index >= 0) {
      warehouses.value[index] = value;
    } else {
      warehouses.value.push(value);
      warehouses.value.sort((left, right) => left.code.localeCompare(right.code));
    }
  }

  async function loadWarehouses(
    context: AdminInventoryAccessContext,
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      loadError.value = "当前会话无权读取仓库事实。";
      return;
    }
    const requestRevision = ++warehouseRevision;
    loadingWarehouses.value = true;
    loadError.value = null;
    try {
      const values = await access.api.warehouses();
      requireCurrent(access);
      if (requestRevision !== warehouseRevision) {
        return;
      }
      warehouses.value = values;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === warehouseRevision) {
        loadError.value = errorMessage(cause, "仓库事实暂时无法读取。");
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === warehouseRevision) {
        loadingWarehouses.value = false;
      }
    }
  }

  async function readStock(
    access: ActiveAccess,
    warehouseId: string,
    skuId: string,
  ): Promise<StockPosition> {
    const value = await access.api.stockPosition(warehouseId, skuId);
    if (value.warehouseId !== warehouseId || value.skuId !== skuId) {
      throw new InventoryContractError(
        "Inventory 已响应，但返回库存与当前仓库或 SKU 不一致。",
      );
    }
    return value;
  }

  async function lookupStock(
    context: AdminInventoryAccessContext,
  ): Promise<StockPosition | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      stockError.value = "当前会话无权读取库存事实。";
      return null;
    }
    const warehouseId = normalizedInput(lookup.warehouseId);
    const skuId = normalizedInput(lookup.skuId);
    const requestRevision = ++stockRevision;
    stockBusy.value = true;
    stockError.value = null;
    try {
      const value = await readStock(access, warehouseId, skuId);
      requireCurrent(access);
      if (requestRevision !== stockRevision) {
        return null;
      }
      stock.value = value;
      return value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === stockRevision) {
        stock.value = null;
        stockError.value = errorMessage(cause, "库存事实暂时无法读取。");
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === stockRevision) {
        stockBusy.value = false;
      }
    }
  }

  function beginPending(
    access: ActiveAccess,
    command: PendingInventoryCommand,
  ) {
    pendingCommand.value = command;
    savePending(access.operatorId, command);
    commandPhase.value = "processing";
    commandMessage.value = `${pendingCommandLabel.value}命令正在等待 Inventory 确认。`;
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

  function adjustmentInput(
    command: PendingInventoryCommand,
  ): AdjustStockInput {
    return {
      movementNo: command.commandKey,
      warehouseId: stringPayload(command.payload, "warehouseId"),
      skuId: stringPayload(command.payload, "skuId"),
      quantityDelta: numberPayload(command.payload, "quantityDelta"),
      reason: stringPayload(command.payload, "reason"),
    };
  }

  async function callCommand(
    api: InventoryApi,
    command: PendingInventoryCommand,
  ): Promise<Warehouse | StockPosition> {
    if (command.kind === "warehouse") {
      return api.createWarehouse(
        stringPayload(command.payload, "code"),
        stringPayload(command.payload, "name"),
      );
    }
    return api.adjustStock(adjustmentInput(command));
  }

  function validateResult(
    command: PendingInventoryCommand,
    value: Warehouse | StockPosition,
  ) {
    if (command.kind === "warehouse") {
      if (
        !("code" in value)
        || value.code !== stringPayload(command.payload, "code")
        || value.name !== stringPayload(command.payload, "name")
      ) {
        throw new InventoryContractError(
          "Inventory 已响应，但返回仓库与当前创建命令不一致。",
        );
      }
      return;
    }
    const input = adjustmentInput(command);
    if (
      !("warehouseId" in value)
      || value.warehouseId !== input.warehouseId
      || value.skuId !== input.skuId
    ) {
      throw new InventoryContractError(
        "Inventory 已响应，但返回库存与当前调整命令不一致。",
      );
    }
  }

  function resetAcceptedForm(
    command: PendingInventoryCommand,
    value: Warehouse | StockPosition,
  ) {
    if (command.kind === "warehouse") {
      warehouseForm.code = "";
      warehouseForm.name = "";
      return;
    }
    const input = adjustmentInput(command);
    lookup.warehouseId = input.warehouseId;
    lookup.skuId = input.skuId;
    adjustment.movementNo = newMovementNo();
    adjustment.quantityDelta = 0;
    adjustment.reason = "";
    if ("warehouseId" in value) {
      stock.value = value;
    }
  }

  async function executePending(
    access: ActiveAccess,
    command: PendingInventoryCommand,
  ): Promise<Warehouse | StockPosition | null> {
    const requestRevision = ++commandRevision;
    submitting.value = true;
    commandPhase.value = "processing";
    commandMessage.value = `${pendingCommandLabel.value}命令正在等待 Inventory 确认。`;
    try {
      const value = await callCommand(access.api, command);
      requireCurrent(access);
      if (requestRevision !== commandRevision) {
        return null;
      }
      validateResult(command, value);
      if ("code" in value) {
        upsertWarehouse(value);
      }
      resetAcceptedForm(command, value);
      settleAccepted(
        access,
        "code" in value
          ? `Inventory 已确认仓库 ${value.code} 已创建。`
          : `Inventory 已确认库存流水 ${command.commandKey} 已应用。`,
      );
      return value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== commandRevision) {
        return null;
      }
      if (resultMayBeUnknown(cause)) {
        commandPhase.value = "unknown";
        commandMessage.value =
          `${errorMessage(cause, "库存命令响应未能确认。")} `
          + "页面没有显示成功；业务号、命令身份和原始载荷均已保留。";
        return null;
      }
      settleRejected(
        access,
        errorMessage(cause, "Inventory 已明确拒绝当前命令。"),
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
    command: PendingInventoryCommand,
  ): Promise<Warehouse | StockPosition | null> {
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

  function createWarehouse(
    context: AdminInventoryAccessContext,
  ): Promise<Warehouse | StockPosition | null> {
    const access = requireAccess(
      context,
      "当前会话无权创建仓库。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条库存命令尚未确认，不能创建第二条命令。";
      return Promise.resolve(null);
    }
    const code = normalizedInput(warehouseForm.code).toUpperCase();
    const name = normalizedInput(warehouseForm.name);
    return runPending(access, {
      kind: "warehouse",
      referenceNo: code,
      commandKey: code,
      payload: { code, name },
      createdAt: new Date().toISOString(),
    });
  }

  function adjustStock(
    context: AdminInventoryAccessContext,
  ): Promise<Warehouse | StockPosition | null> {
    const access = requireAccess(
      context,
      "当前会话无权调整库存。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条库存命令尚未确认，不能生成第二条库存流水。";
      return Promise.resolve(null);
    }
    const quantityDelta = Number(adjustment.quantityDelta);
    return runPending(access, {
      kind: "adjustment",
      referenceNo: adjustment.movementNo,
      commandKey: adjustment.movementNo,
      payload: {
        warehouseId: normalizedInput(adjustment.warehouseId),
        skuId: normalizedInput(adjustment.skuId),
        quantityDelta,
        reason: normalizedInput(adjustment.reason),
      },
      createdAt: new Date().toISOString(),
    });
  }

  function retryPending(
    context: AdminInventoryAccessContext,
  ): Promise<Warehouse | StockPosition | null> {
    const access = requireAccess(
      context,
      "当前会话无权重试库存命令。",
    );
    if (!access || !pendingCommand.value) {
      if (!pendingCommand.value) {
        commandPhase.value = "rejected";
        commandMessage.value = "当前没有可重试的结果未知命令。";
      }
      return Promise.resolve(null);
    }
    if (pendingCommand.value.kind === "warehouse") {
      commandPhase.value = "unknown";
      commandMessage.value =
        "仓库创建接口没有稳定幂等命令键，不能盲目重复 POST；请继续读取仓库列表核对唯一代码与名称。";
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

  async function readPendingAuthority(
    context: AdminInventoryAccessContext,
  ): Promise<Warehouse | StockPosition | null> {
    const access = requireAccess(
      context,
      "当前会话无权读取 Inventory 权威事实。",
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
      if (command.kind === "warehouse") {
        const values = await access.api.warehouses();
        requireCurrent(access);
        if (requestRevision !== commandRevision) {
          return null;
        }
        warehouses.value = values;
        const expectedName = stringPayload(command.payload, "name");
        const matched = values.find((value) => value.code === command.referenceNo);
        if (matched?.name === expectedName) {
          warehouseForm.code = "";
          warehouseForm.name = "";
          settleAccepted(
            access,
            `Inventory 仓库列表已确认 ${matched.code} / ${matched.name}。`,
          );
          return matched;
        }
        commandPhase.value = "unknown";
        commandMessage.value = matched
          ? "相同仓库代码已存在，但名称与原命令不一致，当前事实不能证明原创建命令成功。"
          : "仓库列表尚未出现原代码；原创建命令继续保持结果未知，不能开始第二条命令。";
        return matched ?? null;
      }

      const input = adjustmentInput(command);
      const value = await readStock(access, input.warehouseId, input.skuId);
      requireCurrent(access);
      if (requestRevision !== commandRevision) {
        return null;
      }
      stock.value = value;
      lookup.warehouseId = input.warehouseId;
      lookup.skuId = input.skuId;
      commandPhase.value = "unknown";
      commandMessage.value =
        `Inventory 当前库存为在手 ${value.onHand}、预占 ${value.reserved}、版本 ${value.version}，`
        + "但库存 DTO 不公开 movementNo，不能把当前数量归因于原调整；必须沿用原流水重试确认。";
      return value;
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === commandRevision) {
        commandPhase.value = "unknown";
        commandMessage.value =
          `${errorMessage(cause, "Inventory 权威事实暂时无法读取。")} `
          + "原命令继续保持结果未知。";
      }
      return null;
    } finally {
      if (accessIsCurrent(access) && requestRevision === commandRevision) {
        submitting.value = false;
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
    warehouses,
    stock,
    warehouseForm,
    lookup,
    adjustment,
    loadingWarehouses,
    stockBusy,
    loadError,
    stockError,
    commandPhase,
    commandMessage,
    pendingCommand,
    pendingCommandLabel,
    pendingReferenceNo,
    submitting,
    commandBlocked,
    synchronizeAccess,
    loadWarehouses,
    lookupStock,
    createWarehouse,
    adjustStock,
    retryPending,
    readPendingAuthority,
    resetCommandNotice,
  };
});
