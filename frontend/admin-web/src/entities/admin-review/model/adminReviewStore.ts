import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createCatalogApi,
  type BusinessId,
  type CatalogApi,
  type PageResponse,
  type ProductReview,
  type ReviewModerationResult,
  type ReviewReply,
  type ReviewReport,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PENDING_STORAGE_PREFIX =
  "plain-journal:admin-review:pending-command:v1:";

type ReviewReportStatus = "OPEN" | "RESOLVED";
type ReviewResolution = "UPHELD" | "REJECTED";
type ReviewCommandKind = "reply" | "moderation";

export type ReviewGovernanceCommandPhase =
  | "idle"
  | "processing"
  | "unknown"
  | "accepted"
  | "rejected";

export interface AdminReviewAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: CatalogApi;
}

interface ReviewActionForm {
  replyContent: string;
  replyCommandId: string;
  resolution: ReviewResolution;
  resolutionReason: string;
  moderationCommandId: string;
}

interface PendingReviewCommand {
  kind: ReviewCommandKind;
  reportId: BusinessId;
  reviewId: BusinessId;
  productId: BusinessId;
  rating: number;
  reviewContent: string;
  commandId: string;
  replyContent: string | null;
  resolution: ReviewResolution | null;
  resolutionReason: string | null;
  createdAt: string;
}

export class ReviewGovernanceAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的评价治理结果不会写入当前页面。");
    this.name = "ReviewGovernanceAccessChangedError";
  }
}

export class ReviewGovernanceContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ReviewGovernanceContractError";
  }
}

function isActiveContext(
  context: AdminReviewAccessContext,
): context is {
  authorized: true;
  operatorId: BusinessId;
  accessToken: string;
} {
  return context.authorized
    && isBusinessId(context.operatorId)
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function createApi(accessToken: string): CatalogApi {
  return createCatalogApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function newCommandId(prefix: string): string {
  const suffix = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${prefix}:${suffix}`;
}

function storageKey(operatorId: BusinessId): string {
  return `${PENDING_STORAGE_PREFIX}${operatorId}`;
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
}

function isInstant(value: unknown): value is string {
  return typeof value === "string"
    && value.length > 0
    && Number.isFinite(Date.parse(value));
}

function isNullableInstant(value: unknown): value is string | null {
  return value === null || isInstant(value);
}

function isReviewResolution(value: unknown): value is ReviewResolution {
  return value === "UPHELD" || value === "REJECTED";
}

function isReportStatus(value: unknown): value is ReviewReportStatus {
  return value === "OPEN" || value === "RESOLVED";
}

function validReport(value: unknown): value is ReviewReport {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<ReviewReport>;
  if (
    !isBusinessId(candidate.id)
    || !isBusinessId(candidate.reviewId)
    || !isBusinessId(candidate.productId)
    || !Number.isInteger(candidate.rating)
    || Number(candidate.rating) < 1
    || Number(candidate.rating) > 5
    || typeof candidate.reviewContent !== "string"
    || candidate.reviewContent.length === 0
    || !["SPAM", "ABUSE", "FALSE_INFORMATION", "OTHER"]
      .includes(String(candidate.reasonCode))
    || !(candidate.detail === null || typeof candidate.detail === "string")
    || !isReportStatus(candidate.status)
    || !isInstant(candidate.createdAt)
    || !isNullableInstant(candidate.resolvedAt)
  ) {
    return false;
  }
  if (candidate.status === "OPEN") {
    return candidate.resolution === null && candidate.resolvedAt === null;
  }
  return isReviewResolution(candidate.resolution)
    && candidate.resolvedAt !== null;
}

function validateReportsPage(
  value: PageResponse<ReviewReport>,
  expectedStatus: ReviewReportStatus,
  expectedPage: number,
  expectedSize: number,
): PageResponse<ReviewReport> {
  if (
    !value
    || typeof value !== "object"
    || !Array.isArray(value.items)
    || !value.items.every(validReport)
    || value.items.some((report) => report.status !== expectedStatus)
    || new Set(value.items.map((report) => report.id)).size
      !== value.items.length
    || !Number.isInteger(value.page)
    || value.page !== expectedPage
    || !Number.isInteger(value.size)
    || value.size !== expectedSize
    || !Number.isInteger(value.total)
    || value.total < value.items.length
    || value.items.length > expectedSize
  ) {
    throw new ReviewGovernanceContractError(
      "Catalog 评价举报分页与当前筛选、状态或字符串身份契约不一致。",
    );
  }
  return value;
}

function validReply(value: unknown): value is ReviewReply {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "content" in value
    && typeof value.content === "string"
    && value.content.length > 0
    && "createdAt" in value
    && isInstant(value.createdAt),
  );
}

function validProductReview(value: unknown): value is ProductReview {
  return Boolean(
    value
    && typeof value === "object"
    && "id" in value
    && isBusinessId(value.id)
    && "productId" in value
    && isBusinessId(value.productId)
    && "skuId" in value
    && isBusinessId(value.skuId)
    && "skuName" in value
    && typeof value.skuName === "string"
    && value.skuName.length > 0
    && "specJson" in value
    && typeof value.specJson === "string"
    && "rating" in value
    && Number.isInteger(value.rating)
    && Number(value.rating) >= 1
    && Number(value.rating) <= 5
    && "content" in value
    && typeof value.content === "string"
    && value.content.length > 0
    && "anonymous" in value
    && typeof value.anonymous === "boolean"
    && "authorLabel" in value
    && typeof value.authorLabel === "string"
    && value.authorLabel.length > 0
    && "status" in value
    && ["PUBLISHED", "HIDDEN"].includes(String(value.status))
    && "likeCount" in value
    && Number.isInteger(value.likeCount)
    && Number(value.likeCount) >= 0
    && "likedByViewer" in value
    && typeof value.likedByViewer === "boolean"
    && "reply" in value
    && (value.reply === null || validReply(value.reply))
    && "createdAt" in value
    && isInstant(value.createdAt),
  );
}

function validateReplyResult(
  value: ProductReview,
  command: PendingReviewCommand,
): ProductReview {
  if (
    !validProductReview(value)
    || value.id !== command.reviewId
    || value.productId !== command.productId
    || value.rating !== command.rating
    || value.content !== command.reviewContent
    || value.status !== "PUBLISHED"
    || !value.reply
    || value.reply.content !== command.replyContent
  ) {
    throw new ReviewGovernanceContractError(
      "Catalog 已响应，但平台回复与举报中的评价身份、正文或冻结回复不一致。",
    );
  }
  return value;
}

function validModerationResult(
  value: unknown,
): value is ReviewModerationResult {
  return Boolean(
    value
    && typeof value === "object"
    && "reportId" in value
    && isBusinessId(value.reportId)
    && "reviewId" in value
    && isBusinessId(value.reviewId)
    && "commandId" in value
    && typeof value.commandId === "string"
    && value.commandId.length > 0
    && "resolution" in value
    && isReviewResolution(value.resolution)
    && "reviewStatusBefore" in value
    && ["PUBLISHED", "HIDDEN"].includes(String(value.reviewStatusBefore))
    && "reviewStatusAfter" in value
    && ["PUBLISHED", "HIDDEN"].includes(String(value.reviewStatusAfter))
    && "resolvedAt" in value
    && isInstant(value.resolvedAt),
  );
}

function validateModerationResult(
  value: ReviewModerationResult,
  command: PendingReviewCommand,
): ReviewModerationResult {
  const statusTransitionMatches = command.resolution === "UPHELD"
    ? value.reviewStatusAfter === "HIDDEN"
    : value.reviewStatusAfter === value.reviewStatusBefore;
  if (
    !validModerationResult(value)
    || value.reportId !== command.reportId
    || value.reviewId !== command.reviewId
    || value.commandId !== command.commandId
    || value.resolution !== command.resolution
    || !statusTransitionMatches
  ) {
    throw new ReviewGovernanceContractError(
      "Catalog 已响应，但审核命令身份、举报身份、结论或评价状态迁移不一致。",
    );
  }
  return value;
}

function parsePending(raw: string | null): PendingReviewCommand | null {
  if (!raw) {
    return null;
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== "object") {
      return null;
    }
    const candidate = value as Partial<PendingReviewCommand>;
    const commonValid = ["reply", "moderation"].includes(String(candidate.kind))
      && isBusinessId(candidate.reportId)
      && isBusinessId(candidate.reviewId)
      && isBusinessId(candidate.productId)
      && Number.isInteger(candidate.rating)
      && Number(candidate.rating) >= 1
      && Number(candidate.rating) <= 5
      && typeof candidate.reviewContent === "string"
      && candidate.reviewContent.length > 0
      && typeof candidate.commandId === "string"
      && candidate.commandId.length > 0
      && candidate.commandId.length <= 64
      && isInstant(candidate.createdAt);
    if (!commonValid) {
      return null;
    }
    if (candidate.kind === "reply") {
      return typeof candidate.replyContent === "string"
        && candidate.replyContent.length > 0
        && candidate.replyContent.length <= 1000
        && candidate.resolution === null
        && candidate.resolutionReason === null
        ? candidate as PendingReviewCommand
        : null;
    }
    return candidate.replyContent === null
      && isReviewResolution(candidate.resolution)
      && typeof candidate.resolutionReason === "string"
      && candidate.resolutionReason.length >= 8
      && candidate.resolutionReason.length <= 500
      ? candidate as PendingReviewCommand
      : null;
  } catch {
    return null;
  }
}

function loadPending(
  operatorId: BusinessId,
): PendingReviewCommand | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return parsePending(localStorage.getItem(storageKey(operatorId)));
}

function savePending(
  operatorId: BusinessId,
  command: PendingReviewCommand | null,
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
  if (cause instanceof ReviewGovernanceContractError) {
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

function commandLabel(command: PendingReviewCommand): string {
  return command.kind === "reply" ? "平台公开回复" : "举报审核";
}

export const useAdminReviewStore = defineStore("admin-review", () => {
  const reports = ref<ReviewReport[]>([]);
  const status = ref<ReviewReportStatus>("OPEN");
  const total = ref(0);
  const loading = ref(false);
  const loadError = ref<string | null>(null);
  const refreshedAt = ref<string | null>(null);
  const forms = reactive<Record<string, ReviewActionForm>>({});
  const confirmedReplies = reactive<Record<string, ReviewReply>>({});
  const commandPhase = ref<ReviewGovernanceCommandPhase>("idle");
  const commandMessage = ref<string | null>(null);
  const pendingCommand = ref<PendingReviewCommand | null>(null);
  const submitting = ref(false);
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let reportsRevision = 0;
  let commandRevision = 0;
  let activeCommandPromise:
    Promise<ProductReview | ReviewModerationResult | null> | null = null;

  const pendingCommandLabel = computed(() =>
    pendingCommand.value ? commandLabel(pendingCommand.value) : null);
  const pendingReportId = computed(() =>
    pendingCommand.value?.reportId ?? null);
  const pendingCommandId = computed(() =>
    pendingCommand.value?.commandId ?? null);
  const commandBlocked = computed(() =>
    submitting.value
    || (commandPhase.value === "unknown" && pendingCommand.value !== null));
  const canRetryPending = computed(() =>
    commandPhase.value === "unknown"
    && pendingCommand.value !== null
    && !submitting.value);

  function activeAccess(
    context: AdminReviewAccessContext,
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
      throw new ReviewGovernanceAccessChangedError();
    }
  }

  function clearRecord<T>(record: Record<string, T>) {
    for (const key of Object.keys(record)) {
      delete record[key];
    }
  }

  function reviewForm(reportId: BusinessId): ReviewActionForm {
    forms[reportId] ??= {
      replyContent: "",
      replyCommandId: newCommandId("review-reply"),
      resolution: "REJECTED",
      resolutionReason: "",
      moderationCommandId: newCommandId("review-moderation"),
    };
    return forms[reportId];
  }

  function hydratePending(command: PendingReviewCommand | null) {
    if (!command) {
      return;
    }
    const form = reviewForm(command.reportId);
    if (command.kind === "reply") {
      form.replyContent = command.replyContent ?? "";
      form.replyCommandId = command.commandId;
      return;
    }
    form.resolution = command.resolution ?? "REJECTED";
    form.resolutionReason = command.resolutionReason ?? "";
    form.moderationCommandId = command.commandId;
  }

  function synchronizeAccess(context: AdminReviewAccessContext) {
    const nextOperatorId = isActiveContext(context)
      ? context.operatorId
      : null;
    const nextAccessToken = isActiveContext(context)
      ? context.accessToken
      : null;
    const operatorChanged = activeOperatorId.value !== nextOperatorId;
    const accessChanged = operatorChanged
      || activeAccessToken !== nextAccessToken;
    if (!accessChanged) {
      return activeAccess(context);
    }

    activeOperatorId.value = nextOperatorId;
    activeAccessToken = nextAccessToken;
    accessRevision += 1;
    reportsRevision += 1;
    commandRevision += 1;
    loading.value = false;
    submitting.value = false;
    activeCommandPromise = null;

    if (operatorChanged) {
      reports.value = [];
      status.value = "OPEN";
      total.value = 0;
      loadError.value = null;
      refreshedAt.value = null;
      clearRecord(forms);
      clearRecord(confirmedReplies);
      pendingCommand.value = nextOperatorId
        ? loadPending(nextOperatorId)
        : null;
      hydratePending(pendingCommand.value);
      commandPhase.value = pendingCommand.value ? "unknown" : "idle";
      commandMessage.value = pendingCommand.value
        ? `发现一条尚未确认的${commandLabel(pendingCommand.value)}。`
          + "原命令 ID 与完整载荷已恢复，只能原样重放，不能以举报列表或公开评价猜测命令成功。"
        : null;
    } else if (pendingCommand.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "员工会话凭据已更新，原评价治理命令继续保持结果未知；只能沿用原命令 ID 和载荷。";
    }
    return activeAccess(context);
  }

  function requireAccess(
    context: AdminReviewAccessContext,
    message: string,
  ): ActiveAccess | null {
    const access = synchronizeAccess(context);
    if (!access) {
      commandPhase.value = "rejected";
      commandMessage.value = message;
    }
    return access;
  }

  async function loadReports(
    context: AdminReviewAccessContext,
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      loadError.value = "当前会话无权读取评价治理工作区。";
      return;
    }
    const expectedStatus = status.value;
    const expectedPage = 1;
    const expectedSize = 100;
    const requestRevision = ++reportsRevision;
    loading.value = true;
    loadError.value = null;
    try {
      const value = validateReportsPage(
        await access.api.adminReviewReports(
          expectedStatus,
          expectedPage,
          expectedSize,
        ),
        expectedStatus,
        expectedPage,
        expectedSize,
      );
      requireCurrent(access);
      if (requestRevision !== reportsRevision) {
        return;
      }
      reports.value = value.items;
      total.value = value.total;
      refreshedAt.value = new Date().toISOString();
    } catch (cause) {
      if (accessIsCurrent(access) && requestRevision === reportsRevision) {
        loadError.value = errorMessage(
          cause,
          "Catalog 评价举报暂时无法读取。",
        );
      }
    } finally {
      if (accessIsCurrent(access) && requestRevision === reportsRevision) {
        loading.value = false;
      }
    }
  }

  function beginPending(
    access: ActiveAccess,
    command: PendingReviewCommand,
  ) {
    pendingCommand.value = command;
    savePending(access.operatorId, command);
    commandPhase.value = "processing";
    commandMessage.value =
      `${commandLabel(command)}正在等待 Catalog 确认。`;
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

  function applyModeration(
    command: PendingReviewCommand,
    result: ReviewModerationResult,
  ) {
    const index = reports.value.findIndex((report) =>
      report.id === command.reportId);
    if (index < 0) {
      return;
    }
    const current = reports.value[index];
    if (!current) {
      return;
    }
    const resolved: ReviewReport = {
      ...current,
      status: "RESOLVED",
      resolution: result.resolution,
      resolvedAt: result.resolvedAt,
    };
    if (status.value === "OPEN") {
      reports.value.splice(index, 1);
      total.value = Math.max(0, total.value - 1);
    } else {
      reports.value[index] = resolved;
    }
  }

  async function callPending(
    access: ActiveAccess,
    command: PendingReviewCommand,
  ): Promise<ProductReview | ReviewModerationResult> {
    if (command.kind === "reply") {
      return access.api.replyReview(
        command.reviewId,
        command.replyContent ?? "",
        command.commandId,
      );
    }
    return access.api.resolveReviewReport(command.reportId, {
      commandId: command.commandId,
      resolution: command.resolution ?? "REJECTED",
      reason: command.resolutionReason ?? "",
    });
  }

  async function executePending(
    access: ActiveAccess,
    command: PendingReviewCommand,
  ): Promise<ProductReview | ReviewModerationResult | null> {
    const requestRevision = ++commandRevision;
    submitting.value = true;
    commandPhase.value = "processing";
    commandMessage.value =
      `${commandLabel(command)}正在等待 Catalog 确认。`;
    try {
      const raw = await callPending(access, command);
      requireCurrent(access);
      if (requestRevision !== commandRevision) {
        return null;
      }
      if (command.kind === "reply") {
        const result = validateReplyResult(raw as ProductReview, command);
        confirmedReplies[result.id] = result.reply as ReviewReply;
        settleAccepted(
          access,
          `Catalog 已确认评价 ${result.id} 的平台公开回复。`,
        );
        return result;
      }
      const result = validateModerationResult(
        raw as ReviewModerationResult,
        command,
      );
      applyModeration(command, result);
      const effect = result.resolution === "UPHELD"
        ? result.reviewStatusBefore === "PUBLISHED"
          ? "举报成立，评价已由 PUBLISHED 迁移为 HIDDEN，并从公开评分汇总扣回一次。"
          : "举报成立；评价此前已经是 HIDDEN，本次没有重复扣减评分。"
        : result.reviewStatusAfter === "PUBLISHED"
          ? "举报不成立，评价保持 PUBLISHED。"
          : "举报不成立；评价此前已是 HIDDEN，本次没有把它重新发布。";
      settleAccepted(
        access,
        `Catalog 审核审计已确认命令 ${result.commandId}。${effect}`,
      );
      return result;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== commandRevision) {
        return null;
      }
      if (resultMayBeUnknown(cause)) {
        commandPhase.value = "unknown";
        commandMessage.value =
          `${errorMessage(cause, `${commandLabel(command)}响应未能确认。`)} `
          + "页面没有显示成功；原命令 ID 与完整载荷已冻结。管理举报 GET 不返回命令 ID 或审核原因，公开评价也不能证明命令身份，只能原样重放。";
        return null;
      }
      settleRejected(
        access,
        errorMessage(
          cause,
          `Catalog 已明确拒绝当前${commandLabel(command)}。`,
        ),
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
    command: PendingReviewCommand,
  ): Promise<ProductReview | ReviewModerationResult | null> {
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

  function commandSnapshot(
    report: ReviewReport,
  ): Omit<
    PendingReviewCommand,
    | "kind"
    | "commandId"
    | "replyContent"
    | "resolution"
    | "resolutionReason"
    | "createdAt"
  > {
    return {
      reportId: report.id,
      reviewId: report.reviewId,
      productId: report.productId,
      rating: report.rating,
      reviewContent: report.reviewContent,
    };
  }

  function reply(
    report: ReviewReport,
    context: AdminReviewAccessContext,
  ): Promise<ProductReview | ReviewModerationResult | null> {
    const access = requireAccess(
      context,
      "当前会话无权保存平台公开回复。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条评价治理命令尚未确认，不能创建第二条回复或审核命令。";
      return Promise.resolve(null);
    }
    if (!validReport(report) || report.status !== "OPEN") {
      commandPhase.value = "rejected";
      commandMessage.value = "只有身份完整的开放举报可以保存平台回复。";
      return Promise.resolve(null);
    }
    const form = reviewForm(report.id);
    const content = form.replyContent.trim();
    if (!content || content.length > 1000) {
      commandPhase.value = "rejected";
      commandMessage.value = "平台公开回复必须为 1–1000 个字符。";
      return Promise.resolve(null);
    }
    if (confirmedReplies[report.reviewId]) {
      commandPhase.value = "rejected";
      commandMessage.value = "当前页面已经确认该评价的平台回复，不会提交第二条回复。";
      return Promise.resolve(null);
    }
    return runPending(access, {
      kind: "reply",
      ...commandSnapshot(report),
      commandId: form.replyCommandId,
      replyContent: content,
      resolution: null,
      resolutionReason: null,
      createdAt: new Date().toISOString(),
    });
  }

  function moderate(
    report: ReviewReport,
    context: AdminReviewAccessContext,
  ): Promise<ProductReview | ReviewModerationResult | null> {
    const access = requireAccess(
      context,
      "当前会话无权审核评价举报。",
    );
    if (!access) {
      return Promise.resolve(null);
    }
    if (commandBlocked.value) {
      commandPhase.value = "unknown";
      commandMessage.value =
        "上一条评价治理命令尚未确认，不能创建第二条回复或审核命令。";
      return Promise.resolve(null);
    }
    if (!validReport(report) || report.status !== "OPEN") {
      commandPhase.value = "rejected";
      commandMessage.value = "只有身份完整的开放举报可以提交审核结论。";
      return Promise.resolve(null);
    }
    const form = reviewForm(report.id);
    const reason = form.resolutionReason.trim();
    if (reason.length < 8 || reason.length > 500) {
      commandPhase.value = "rejected";
      commandMessage.value = "审核说明必须为 8–500 个字符。";
      return Promise.resolve(null);
    }
    return runPending(access, {
      kind: "moderation",
      ...commandSnapshot(report),
      commandId: form.moderationCommandId,
      replyContent: null,
      resolution: form.resolution,
      resolutionReason: reason,
      createdAt: new Date().toISOString(),
    });
  }

  function retryPending(
    context: AdminReviewAccessContext,
  ): Promise<ProductReview | ReviewModerationResult | null> {
    const access = requireAccess(
      context,
      "当前会话无权重试评价治理命令。",
    );
    const command = pendingCommand.value;
    if (!access || !command) {
      if (!command) {
        commandPhase.value = "rejected";
        commandMessage.value = "当前没有可重试的结果未知命令。";
      }
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
    reports,
    status,
    total,
    loading,
    loadError,
    refreshedAt,
    confirmedReplies,
    commandPhase,
    commandMessage,
    pendingCommand,
    pendingCommandLabel,
    pendingReportId,
    pendingCommandId,
    submitting,
    commandBlocked,
    canRetryPending,
    synchronizeAccess,
    reviewForm,
    loadReports,
    reply,
    moderate,
    retryPending,
    resetCommandNotice,
  };
});
