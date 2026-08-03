import { computed, reactive, ref } from "vue";
import { defineStore } from "pinia";

import {
  createAnalyticsApi,
  createApiClient,
  type AnalyticsApi,
  type AnalyticsDashboard,
  type AnalyticsDailySummary,
  type AnalyticsOverviewTotals,
  type AnalyticsProductSummary,
  type AnalyticsProjectionFreshness,
  type BusinessId,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const PRODUCT_LIMIT = 8;
const MAXIMUM_RANGE_DAYS = 366;
const DAY_MILLISECONDS = 86_400_000;
const COUNT_KEYS = [
  "createdOrderCount",
  "paymentCount",
  "completedOrderCount",
  "closedOrderCount",
  "afterSaleCount",
  "refundCount",
] as const;
const MONEY_KEYS = [
  "createdOrderAmount",
  "paymentAmount",
  "completedOrderAmount",
  "afterSaleAmount",
  "refundAmount",
] as const;

export interface AdminAnalyticsAccessContext {
  authorized: boolean;
  operatorId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAccess {
  operatorId: BusinessId;
  accessToken: string;
  revision: number;
  api: AnalyticsApi;
}

export class AnalyticsAccessChangedError extends Error {
  constructor() {
    super("员工账户或会话已切换，旧的运营投影不会写入当前页面。");
    this.name = "AnalyticsAccessChangedError";
  }
}

export class AnalyticsProjectionContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AnalyticsProjectionContractError";
  }
}

function localDate(daysBefore = 0): string {
  const value = new Date();
  value.setDate(value.getDate() - daysBefore);
  const offset = value.getTimezoneOffset() * 60_000;
  return new Date(value.getTime() - offset).toISOString().slice(0, 10);
}

function isBusinessId(value: unknown): value is BusinessId {
  return typeof value === "string" && /^[0-9]+$/u.test(value);
}

function isDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/u.test(value)) {
    return false;
  }
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(timestamp)
    && new Date(timestamp).toISOString().slice(0, 10) === value;
}

function dateTimestamp(value: string): number {
  return Date.parse(`${value}T00:00:00Z`);
}

function rangeDays(from: string, to: string): number {
  return Math.floor(
    (dateTimestamp(to) - dateTimestamp(from)) / DAY_MILLISECONDS,
  ) + 1;
}

function validateRange(from: string, to: string): number {
  if (!isDate(from) || !isDate(to)) {
    throw new AnalyticsProjectionContractError(
      "运营统计日期必须使用有效的 YYYY-MM-DD 日历日期。",
    );
  }
  const days = rangeDays(from, to);
  if (days < 1) {
    throw new AnalyticsProjectionContractError(
      "运营统计开始日期不能晚于结束日期。",
    );
  }
  if (days > MAXIMUM_RANGE_DAYS) {
    throw new AnalyticsProjectionContractError(
      `运营统计单次读取不能超过 ${MAXIMUM_RANGE_DAYS} 天。`,
    );
  }
  return days;
}

function isInstant(value: unknown): value is string {
  return typeof value === "string"
    && value.length > 0
    && Number.isFinite(Date.parse(value));
}

function isNonNegativeCount(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isMoney(value: unknown): value is number {
  return typeof value === "number"
    && Number.isFinite(value)
    && value >= 0;
}

function validTotals(value: unknown): value is AnalyticsOverviewTotals {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<AnalyticsOverviewTotals>;
  return COUNT_KEYS.every((key) => isNonNegativeCount(candidate[key]))
    && MONEY_KEYS.every((key) => isMoney(candidate[key]))
    && isNonNegativeCount(candidate.uniqueCustomers);
}

function validDailySummary(value: unknown): value is AnalyticsDailySummary {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<AnalyticsDailySummary>;
  return isDate(candidate.businessDate)
    && COUNT_KEYS.every((key) => isNonNegativeCount(candidate[key]))
    && MONEY_KEYS.every((key) => isMoney(candidate[key]))
    && isInstant(candidate.updatedAt);
}

function validProductSummary(
  value: unknown,
): value is AnalyticsProductSummary {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<AnalyticsProductSummary>;
  return isBusinessId(candidate.productId)
    && typeof candidate.productTitle === "string"
    && candidate.productTitle.trim().length > 0
    && isNonNegativeCount(candidate.completedOrderCount)
    && isNonNegativeCount(candidate.unitsSold)
    && isMoney(candidate.netRevenue)
    && isNonNegativeCount(candidate.revenueCoveredOrderCount)
    && Number(candidate.revenueCoveredOrderCount)
      <= Number(candidate.completedOrderCount);
}

function validFreshness(
  value: unknown,
): value is AnalyticsProjectionFreshness {
  if (!value || typeof value !== "object") {
    return false;
  }
  const candidate = value as Partial<AnalyticsProjectionFreshness>;
  return isNonNegativeCount(candidate.sourceEventCount)
    && (
      candidate.lastConsumedAt === null
      || isInstant(candidate.lastConsumedAt)
    )
    && isInstant(candidate.generatedAt);
}

function validateDashboard(
  value: AnalyticsDashboard,
  expectedFrom: string,
  expectedTo: string,
  expectedDays: number,
): AnalyticsDashboard {
  if (!value || typeof value !== "object") {
    throw new AnalyticsProjectionContractError(
      "Analytics 运营投影缺少统一对象结构。",
    );
  }
  const candidate = value as Partial<AnalyticsDashboard>;
  if (
    candidate.from !== expectedFrom
    || candidate.to !== expectedTo
    || !validTotals(candidate.totals)
    || !Array.isArray(candidate.daily)
    || !candidate.daily.every(validDailySummary)
    || candidate.daily.length > expectedDays
    || !Array.isArray(candidate.topProducts)
    || !candidate.topProducts.every(validProductSummary)
    || candidate.topProducts.length > PRODUCT_LIMIT
    || !validFreshness(candidate.freshness)
  ) {
    throw new AnalyticsProjectionContractError(
      "Analytics 运营投影与当前日期、计数、金额或字符串身份契约不一致。",
    );
  }

  const dailyDates = candidate.daily.map((summary) => summary.businessDate);
  const sortedDailyDates = [...dailyDates].sort();
  if (
    new Set(dailyDates).size !== dailyDates.length
    || dailyDates.some((date) => date < expectedFrom || date > expectedTo)
    || dailyDates.some((date, index) => date !== sortedDailyDates[index])
    || new Set(candidate.topProducts.map((product) => product.productId)).size
      !== candidate.topProducts.length
  ) {
    throw new AnalyticsProjectionContractError(
      "Analytics 日汇总或商品汇总包含重复、乱序或越界事实。",
    );
  }
  return candidate as AnalyticsDashboard;
}

function isActiveContext(
  context: AdminAnalyticsAccessContext,
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

function createApi(accessToken: string): AnalyticsApi {
  return createAnalyticsApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 10000,
    tokenProvider: () => accessToken,
  }));
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error
    ? cause.message
    : "运营统计暂时无法读取。";
}

export const useAdminAnalyticsStore = defineStore("admin-analytics", () => {
  const dashboard = ref<AnalyticsDashboard | null>(null);
  const range = reactive({
    from: localDate(29),
    to: localDate(),
  });
  const loading = ref(false);
  const error = ref<string | null>(null);
  const refreshedAt = ref<string | null>(null);
  const activeOperatorId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let requestRevision = 0;

  const hasKnownProjection = computed(() => dashboard.value !== null);
  const recentDaily = computed(() =>
    dashboard.value?.daily.slice(-7).reverse() ?? []);

  function activeAccess(
    context: AdminAnalyticsAccessContext,
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
      throw new AnalyticsAccessChangedError();
    }
  }

  function synchronizeAccess(context: AdminAnalyticsAccessContext) {
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
    requestRevision += 1;
    loading.value = false;

    if (operatorChanged) {
      dashboard.value = null;
      error.value = null;
      refreshedAt.value = null;
    }
    return activeAccess(context);
  }

  async function load(
    context: AdminAnalyticsAccessContext,
  ): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      error.value = "当前会话无权读取平台运营统计。";
      return;
    }

    let expectedDays: number;
    try {
      expectedDays = validateRange(range.from, range.to);
    } catch (cause) {
      error.value = errorMessage(cause);
      return;
    }

    const expectedFrom = range.from;
    const expectedTo = range.to;
    const currentRequest = ++requestRevision;
    loading.value = true;
    error.value = null;
    try {
      const value = validateDashboard(
        await access.api.overview(
          expectedFrom,
          expectedTo,
          PRODUCT_LIMIT,
        ),
        expectedFrom,
        expectedTo,
        expectedDays,
      );
      requireCurrent(access);
      if (currentRequest !== requestRevision) {
        return;
      }
      dashboard.value = value;
      refreshedAt.value = new Date().toISOString();
    } catch (cause) {
      if (accessIsCurrent(access) && currentRequest === requestRevision) {
        error.value = errorMessage(cause);
      }
    } finally {
      if (accessIsCurrent(access) && currentRequest === requestRevision) {
        loading.value = false;
      }
    }
  }

  return {
    dashboard,
    range,
    loading,
    error,
    refreshedAt,
    hasKnownProjection,
    recentDaily,
    synchronizeAccess,
    load,
  };
});
