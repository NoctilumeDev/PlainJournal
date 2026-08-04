import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createCatalogApi,
  createInventoryApi,
  createMarketingApi,
  createTradeApi,
  secureRandomUUID,
  multiplyMoney,
  sumMoney,
  type Benefit,
  type BusinessId,
  type CreateOrderInput,
  type Order,
  type PricingPreview,
} from "@plain-journal/foundation";

import {
  type AddressAccessContext,
  useAddressStore,
} from "../../../entities/address";
import {
  type AccountCartAccessContext,
  useAccountCartStore,
} from "../../../entities/account-cart";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";
const LEGACY_PENDING_ORDER_KEY = "plain-journal:pending-order:v1";
const PENDING_ORDER_KEY_PREFIX = "plain-journal:pending-order:v2:";
const AUTHORITY_TTL_MS = 60_000;

export interface CheckoutAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveCheckoutAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export class CheckoutAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的结算请求结果不会写入当前页面。");
    this.name = "CheckoutAccessChangedError";
  }
}

export class CheckoutDraftChangedError extends Error {
  constructor() {
    super("结算内容已变化，旧的核对结果不会写入当前页面。");
    this.name = "CheckoutDraftChangedError";
  }
}

export interface CheckoutAuthorityLine {
  cartItemId: BusinessId;
  productId: BusinessId;
  skuId: BusinessId;
  productTitle: string;
  skuName: string;
  quantity: number;
  cartUnitPrice: string | number;
  currentUnitPrice: string | number;
  available: number;
  priceChanged: boolean;
}

export interface CheckoutAuthoritySnapshot {
  lines: CheckoutAuthorityLine[];
  preview: PricingPreview;
  checkedAt: string;
}

export interface PendingOrderSubmission {
  key: string;
  userId: BusinessId;
  request: CreateOrderInput;
  createdAt: string;
}

function isPendingOrderSubmission(value: unknown): value is PendingOrderSubmission {
  return Boolean(
    value
    && typeof value === "object"
    && "key" in value
    && "userId" in value
    && "request" in value
    && "createdAt" in value
    && typeof value.key === "string"
    && value.key.startsWith("order:")
    && typeof value.userId === "string"
    && value.userId.length > 0
    && typeof value.createdAt === "string"
    && value.request
    && typeof value.request === "object",
  );
}

function pendingOrderKey(ownerId: BusinessId): string {
  return `${PENDING_ORDER_KEY_PREFIX}${ownerId}`;
}

function readPendingOrder(key: string): PendingOrderSubmission | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? "null");
    return isPendingOrderSubmission(value) ? value : null;
  } catch {
    return null;
  }
}

function loadPendingOrder(ownerId: BusinessId): PendingOrderSubmission | null {
  const ownerKey = pendingOrderKey(ownerId);
  const current = readPendingOrder(ownerKey);
  if (current?.userId === ownerId) {
    return current;
  }
  const legacy = readPendingOrder(LEGACY_PENDING_ORDER_KEY);
  if (legacy?.userId !== ownerId || typeof localStorage === "undefined") {
    return null;
  }
  localStorage.setItem(ownerKey, JSON.stringify(legacy));
  localStorage.removeItem(LEGACY_PENDING_ORDER_KEY);
  return legacy;
}

function isActiveContext(context: CheckoutAccessContext): context is {
  authenticated: true;
  ownerId: BusinessId;
  accessToken: string;
} {
  return context.authenticated
    && typeof context.ownerId === "string"
    && context.ownerId.length > 0
    && typeof context.accessToken === "string"
    && context.accessToken.length > 0;
}

function newOrderKey(): string {
  return `order:${secureRandomUUID()}`;
}

function normalizeMoney(value: string | number): string {
  return multiplyMoney(value, 1);
}

export const useCheckoutStore = defineStore("checkout-draft", () => {
  const benefits = ref<Benefit[]>([]);
  const selectedAddressId = ref<BusinessId | null>(null);
  const selectedBenefitNos = ref<string[]>([]);
  const preview = ref<PricingPreview | null>(null);
  const loading = ref(false);
  const previewing = ref(false);
  const error = ref<string | null>(null);
  const previewError = ref<string | null>(null);
  const authority = ref<CheckoutAuthoritySnapshot | null>(null);
  const authorityChecking = ref(false);
  const authorityError = ref<string | null>(null);
  const pendingSubmission = ref<PendingOrderSubmission | null>(null);
  const submitting = ref(false);
  const resolvingSubmission = ref(false);
  const submissionUnknown = ref(false);
  const submissionError = ref<string | null>(null);
  const lastOrder = ref<Order | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  const addresses = useAddressStore();
  const cart = useAccountCartStore();
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let loadRevision = 0;
  let calculationRevision = 0;
  let submissionRevision = 0;
  let activeSubmissionPromise: Promise<Order | null> | null = null;

  const selectedAddress = computed(() =>
    addresses.addresses.find((address) => address.id === selectedAddressId.value) ?? null);
  const availableBenefits = computed(() =>
    benefits.value.filter((benefit) => benefit.status === "AVAILABLE"));
  const originalAmount = computed(() => sumMoney(
    cart.selectedItems.map((item) => multiplyMoney(item.unitPrice, item.quantity)),
  ));
  const readyForPreview = computed(() =>
    Boolean(selectedAddress.value && cart.selectedItems.length > 0));
  const authorityReady = computed(() => Boolean(
    authority.value
    && authority.value.lines.length > 0
    && authority.value.lines.every((line) => line.available >= line.quantity),
  ));
  const authorityHasPriceChanges = computed(() =>
    authority.value?.lines.some((line) => line.priceChanged) ?? false);
  const displayPreview = computed(() => authority.value?.preview ?? preview.value);

  function asAddressAccess(context: CheckoutAccessContext): AddressAccessContext {
    return context;
  }

  function asAccountCartAccess(context: CheckoutAccessContext): AccountCartAccessContext {
    return context;
  }

  function synchronizeAccess(context: CheckoutAccessContext): ActiveCheckoutAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const accessChanged = ownerChanged || activeAccessToken !== nextAccessToken;

    if (accessChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      loadRevision += 1;
      calculationRevision += 1;
      submissionRevision += 1;
      activeSubmissionPromise = null;
      loading.value = false;
      previewing.value = false;
      authorityChecking.value = false;
      submitting.value = false;
      resolvingSubmission.value = false;
      error.value = null;
      previewError.value = null;
      authority.value = null;
      authorityError.value = null;
      submissionError.value = null;
      submissionUnknown.value = false;
      lastOrder.value = null;

      if (ownerChanged) {
        benefits.value = [];
        selectedAddressId.value = null;
        selectedBenefitNos.value = [];
        preview.value = null;
        pendingSubmission.value = nextOwnerId ? loadPendingOrder(nextOwnerId) : null;
      }
    }

    if (!isActiveContext(context)) {
      benefits.value = [];
      selectedAddressId.value = null;
      selectedBenefitNos.value = [];
      preview.value = null;
      pendingSubmission.value = null;
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveCheckoutAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveCheckoutAccess) {
    if (!accessIsCurrent(access)) {
      throw new CheckoutAccessChangedError();
    }
  }

  function clientFor(access: ActiveCheckoutAccess) {
    return createApiClient({
      baseUrl: apiBaseUrl,
      timeoutMs: 8000,
      tokenProvider: () => access.accessToken,
    });
  }

  function currentDraftFingerprint(): string {
    return JSON.stringify({
      ownerId: activeOwnerId.value,
      addressId: selectedAddressId.value,
      benefitNos: [...selectedBenefitNos.value].sort(),
      items: cart.selectedItems
        .map((item) => ({
          id: item.id,
          productId: item.productId,
          skuId: item.skuId,
          quantity: item.quantity,
          unitPrice: normalizeMoney(item.unitPrice),
        }))
        .sort((left, right) => left.skuId.localeCompare(right.skuId)),
    });
  }

  function requireCurrentDraft(
    access: ActiveCheckoutAccess,
    requestCalculationRevision: number,
    fingerprint: string,
  ) {
    requireCurrent(access);
    if (
      requestCalculationRevision !== calculationRevision
      || fingerprint !== currentDraftFingerprint()
    ) {
      throw new CheckoutDraftChangedError();
    }
  }

  async function load(context: CheckoutAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      return;
    }
    const requestRevision = ++loadRevision;
    loading.value = true;
    error.value = null;
    previewError.value = null;
    authority.value = null;
    authorityError.value = null;
    try {
      const marketingApi = createMarketingApi(clientFor(access));
      const [, , loadedBenefits] = await Promise.all([
        addresses.load(asAddressAccess(context)),
        cart.load(asAccountCartAccess(context)),
        marketingApi.benefits(),
      ]);
      requireCurrent(access);
      if (requestRevision !== loadRevision) {
        throw new CheckoutAccessChangedError();
      }
      benefits.value = loadedBenefits;
      const currentAddressStillExists = addresses.addresses.some(
        (address) => address.id === selectedAddressId.value,
      );
      if (!currentAddressStillExists) {
        selectedAddressId.value = addresses.defaultAddress?.id
          ?? addresses.addresses[0]?.id
          ?? null;
      }
      selectedBenefitNos.value = selectedBenefitNos.value.filter((benefitNo) =>
        availableBenefits.value.some((benefit) => benefit.benefitNo === benefitNo));
      calculationRevision += 1;
      if (readyForPreview.value) {
        await refreshPreview(context);
      } else {
        preview.value = null;
      }
      await recoverPendingSubmission(context, true);
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== loadRevision) {
        throw new CheckoutAccessChangedError();
      }
      error.value = cause instanceof Error ? cause.message : "结算草稿暂时无法读取。";
      throw cause;
    } finally {
      if (accessIsCurrent(access) && requestRevision === loadRevision) {
        loading.value = false;
      }
    }
  }

  function selectAddress(addressId: BusinessId) {
    selectedAddressId.value = addressId;
    invalidateCalculations();
  }

  function toggleBenefit(benefit: Benefit) {
    if (selectedBenefitNos.value.includes(benefit.benefitNo)) {
      selectedBenefitNos.value = selectedBenefitNos.value.filter(
        (benefitNo) => benefitNo !== benefit.benefitNo,
      );
    } else {
      const sameTypeNos = new Set(availableBenefits.value
        .filter((candidate) => candidate.benefitType === benefit.benefitType)
        .map((candidate) => candidate.benefitNo));
      selectedBenefitNos.value = selectedBenefitNos.value.filter(
        (benefitNo) => !sameTypeNos.has(benefitNo),
      );
      selectedBenefitNos.value.push(benefit.benefitNo);
    }
    invalidateCalculations();
  }

  function invalidateCalculations() {
    calculationRevision += 1;
    preview.value = null;
    previewError.value = null;
    authority.value = null;
    authorityError.value = null;
  }

  async function refreshPreview(context: CheckoutAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      preview.value = null;
      return;
    }
    const address = selectedAddress.value;
    if (!address || cart.selectedItems.length === 0) {
      preview.value = null;
      return;
    }
    const requestCalculationRevision = calculationRevision;
    const fingerprint = currentDraftFingerprint();
    const selectedItems = [...cart.selectedItems];
    const benefitNos = [...selectedBenefitNos.value];
    const marketingApi = createMarketingApi(clientFor(access));
    previewing.value = true;
    previewError.value = null;
    try {
      const result = await marketingApi.previewPricing({
        originalAmount: sumMoney(selectedItems.map((item) =>
          multiplyMoney(item.unitPrice, item.quantity))),
        deliveryRegion: {
          provinceCode: address.provinceCode,
          cityCode: address.cityCode,
          districtCode: address.districtCode,
        },
        lines: selectedItems.map((item, index) => ({
          lineNo: index + 1,
          skuId: item.skuId,
          lineAmount: multiplyMoney(item.unitPrice, item.quantity),
        })),
        benefitNos,
      });
      requireCurrentDraft(access, requestCalculationRevision, fingerprint);
      preview.value = result;
    } catch (cause) {
      if (
        cause instanceof CheckoutAccessChangedError
        || cause instanceof CheckoutDraftChangedError
      ) {
        throw cause;
      }
      preview.value = null;
      previewError.value = cause instanceof Error ? cause.message : "优惠试算未完成。";
      throw cause;
    } finally {
      if (accessIsCurrent(access)) {
        previewing.value = false;
      }
    }
  }

  async function refreshAuthority(context: CheckoutAccessContext) {
    const access = synchronizeAccess(context);
    if (!access) {
      authority.value = null;
      return;
    }
    const address = selectedAddress.value;
    if (!address || cart.selectedItems.length === 0) {
      authority.value = null;
      return;
    }
    const requestCalculationRevision = calculationRevision;
    const fingerprint = currentDraftFingerprint();
    const selectedItems = [...cart.selectedItems];
    const benefitNos = [...selectedBenefitNos.value];
    const client = clientFor(access);
    const catalogApi = createCatalogApi(client);
    const inventoryApi = createInventoryApi(client);
    const marketingApi = createMarketingApi(client);
    authorityChecking.value = true;
    authorityError.value = null;
    try {
      const productRequests = new Map<BusinessId, ReturnType<typeof catalogApi.getProduct>>();
      const lines = await Promise.all(selectedItems.map(async (item) => {
        let productRequest = productRequests.get(item.productId);
        if (!productRequest) {
          productRequest = catalogApi.getProduct(item.productId);
          productRequests.set(item.productId, productRequest);
        }
        const [product, stock] = await Promise.all([
          productRequest,
          inventoryApi.stock(item.skuId),
        ]);
        const sku = product.skus.find((candidate) =>
          candidate.id === item.skuId && candidate.status === "ACTIVE");
        if (product.status !== "ACTIVE" || !sku) {
          throw new Error(`${item.productTitle} 当前不可提交。`);
        }
        return {
          cartItemId: item.id,
          productId: item.productId,
          skuId: item.skuId,
          productTitle: product.title,
          skuName: sku.name,
          quantity: item.quantity,
          cartUnitPrice: item.unitPrice,
          currentUnitPrice: sku.salePrice,
          available: stock.available,
          priceChanged: normalizeMoney(item.unitPrice) !== normalizeMoney(sku.salePrice),
        } satisfies CheckoutAuthorityLine;
      }));
      const originalAmount = sumMoney(lines.map((line) =>
        multiplyMoney(line.currentUnitPrice, line.quantity)));
      const authoritativePreview = await marketingApi.previewPricing({
        originalAmount,
        deliveryRegion: {
          provinceCode: address.provinceCode,
          cityCode: address.cityCode,
          districtCode: address.districtCode,
        },
        lines: lines.map((line, index) => ({
          lineNo: index + 1,
          skuId: line.skuId,
          lineAmount: multiplyMoney(line.currentUnitPrice, line.quantity),
        })),
        benefitNos,
      });
      requireCurrentDraft(access, requestCalculationRevision, fingerprint);
      authority.value = {
        lines,
        preview: authoritativePreview,
        checkedAt: new Date().toISOString(),
      };
      preview.value = authoritativePreview;
    } catch (cause) {
      if (
        cause instanceof CheckoutAccessChangedError
        || cause instanceof CheckoutDraftChangedError
      ) {
        throw cause;
      }
      authority.value = null;
      authorityError.value = cause instanceof Error
        ? cause.message
        : "当前价格、库存或优惠资格未能完成权威核对。";
      throw cause;
    } finally {
      if (accessIsCurrent(access)) {
        authorityChecking.value = false;
      }
    }
  }

  function buildOrderRequest(): CreateOrderInput {
    const snapshot = authority.value;
    const addressId = selectedAddressId.value;
    if (!snapshot || !addressId) {
      throw new Error("请先完成提交前权威核对。");
    }
    return {
      addressId,
      items: snapshot.lines
        .map((line) => ({
          productId: line.productId,
          skuId: line.skuId,
          quantity: line.quantity,
        }))
        .sort((left, right) =>
          left.skuId.localeCompare(right.skuId)
          || left.productId.localeCompare(right.productId)),
      benefitNos: [...selectedBenefitNos.value].sort(),
    };
  }

  function persistPendingSubmission(
    access: ActiveCheckoutAccess,
    pending: PendingOrderSubmission | null,
  ) {
    requireCurrent(access);
    pendingSubmission.value = pending;
    if (typeof localStorage === "undefined") {
      return;
    }
    const storageKey = pendingOrderKey(access.ownerId);
    if (pending) {
      localStorage.setItem(storageKey, JSON.stringify(pending));
    } else {
      localStorage.removeItem(storageKey);
    }
  }

  function preparePendingSubmission(
    access: ActiveCheckoutAccess,
  ): PendingOrderSubmission {
    const request = buildOrderRequest();
    const existing = pendingSubmission.value;
    if (existing) {
      if (existing.userId !== access.ownerId) {
        throw new Error("当前账户的待确认订单存储不一致，页面不会接管它。");
      }
      if (JSON.stringify(existing.request) !== JSON.stringify(request)) {
        throw new Error("已有一笔结果尚未确认的订单，不能换内容或换幂等键重新提交。");
      }
      return existing;
    }
    const pending: PendingOrderSubmission = {
      key: newOrderKey(),
      userId: access.ownerId,
      request,
      createdAt: new Date().toISOString(),
    };
    persistPendingSubmission(access, pending);
    return pending;
  }

  function completePendingSubmission(
    access: ActiveCheckoutAccess,
    order: Order,
  ) {
    requireCurrent(access);
    lastOrder.value = order;
    submissionUnknown.value = false;
    submissionError.value = null;
    persistPendingSubmission(access, null);
  }

  async function recoverPendingSubmission(
    context: CheckoutAccessContext,
    silent = false,
  ): Promise<Order | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      return null;
    }
    const pending = pendingSubmission.value;
    if (!pending) {
      return null;
    }
    if (pending.userId !== access.ownerId) {
      submissionUnknown.value = true;
      submissionError.value = "当前账户的待确认订单存储不一致，页面不会查询或重试它。";
      return null;
    }
    resolvingSubmission.value = true;
    try {
      const tradeApi = createTradeApi(clientFor(access));
      const order = await tradeApi.orderByIdempotencyKey(pending.key);
      requireCurrent(access);
      completePendingSubmission(access, order);
      return order;
    } catch (cause) {
      if (!accessIsCurrent(access) || cause instanceof CheckoutAccessChangedError) {
        throw new CheckoutAccessChangedError();
      }
      submissionUnknown.value = true;
      if (cause instanceof ApiError && cause.status === 404) {
        if (!silent) {
          submissionError.value = "暂未查询到订单事实。可以使用原请求键安全重试，不能换键重提。";
        }
        return null;
      }
      submissionError.value = cause instanceof Error
        ? `订单结果查询未完成：${cause.message}`
        : "订单结果查询未完成。";
      return null;
    } finally {
      if (accessIsCurrent(access)) {
        resolvingSubmission.value = false;
      }
    }
  }

  async function submitOrderOnce(
    context: CheckoutAccessContext,
    access: ActiveCheckoutAccess,
    requestRevision: number,
  ): Promise<Order | null> {
    submissionError.value = null;
    const recovered = await recoverPendingSubmission(context, true);
    requireCurrent(access);
    if (requestRevision !== submissionRevision) {
      throw new CheckoutAccessChangedError();
    }
    if (recovered) {
      return recovered;
    }
    let pending: PendingOrderSubmission;
    if (pendingSubmission.value) {
      if (pendingSubmission.value.userId !== access.ownerId) {
        submissionError.value = "当前账户的待确认订单存储不一致，页面不会重试它。";
        return null;
      }
      pending = pendingSubmission.value;
    } else {
      const snapshot = authority.value;
      if (!snapshot || !authorityReady.value) {
        submissionError.value = "请先完成价格、库存和优惠资格核对，并处理库存不足。";
        return null;
      }
      const checkedAt = Date.parse(snapshot.checkedAt);
      if (!Number.isFinite(checkedAt) || Date.now() - checkedAt > AUTHORITY_TTL_MS) {
        authority.value = null;
        submissionError.value = "权威核对已经超过 60 秒，请重新核对后提交。";
        return null;
      }
      try {
        pending = preparePendingSubmission(access);
      } catch (cause) {
        submissionError.value = cause instanceof Error ? cause.message : "订单请求无法准备。";
        return null;
      }
    }
    submitting.value = true;
    try {
      const tradeApi = createTradeApi(clientFor(access));
      const order = await tradeApi.createOrder(pending.request, pending.key);
      requireCurrent(access);
      if (requestRevision !== submissionRevision) {
        throw new CheckoutAccessChangedError();
      }
      completePendingSubmission(access, order);
      return order;
    } catch (cause) {
      if (!accessIsCurrent(access) || cause instanceof CheckoutAccessChangedError) {
        throw new CheckoutAccessChangedError();
      }
      const order = await recoverPendingSubmission(context, true);
      requireCurrent(access);
      if (requestRevision !== submissionRevision) {
        throw new CheckoutAccessChangedError();
      }
      if (order) {
        return order;
      }
      const uncertain = cause instanceof ApiError && (
        cause.kind === "network"
        || cause.kind === "timeout"
        || cause.kind === "invalid-response"
        || (cause.kind === "http" && (cause.status ?? 500) >= 500)
      );
      if (uncertain) {
        submissionUnknown.value = true;
        submissionError.value = "订单提交结果尚未确认。请求快照和幂等键已保留，请查询或使用原请求安全重试。";
        return null;
      }
      persistPendingSubmission(access, null);
      submissionUnknown.value = false;
      submissionError.value = cause instanceof Error ? cause.message : "订单提交未完成。";
      return null;
    }
  }

  function submitOrder(context: CheckoutAccessContext): Promise<Order | null> {
    const access = synchronizeAccess(context);
    if (!access) {
      submissionError.value = "当前会话没有可用于提交订单的账户事实。";
      return Promise.resolve(null);
    }
    if (activeSubmissionPromise) {
      return activeSubmissionPromise;
    }
    const requestRevision = ++submissionRevision;
    const request = submitOrderOnce(context, access, requestRevision)
      .finally(() => {
        if (
          accessIsCurrent(access)
          && requestRevision === submissionRevision
          && activeSubmissionPromise === request
        ) {
          activeSubmissionPromise = null;
          submitting.value = false;
        }
      });
    activeSubmissionPromise = request;
    return request;
  }

  return {
    benefits,
    availableBenefits,
    selectedAddressId,
    selectedAddress,
    selectedBenefitNos,
    preview,
    loading,
    previewing,
    error,
    previewError,
    authority,
    authorityChecking,
    authorityError,
    authorityReady,
    authorityHasPriceChanges,
    displayPreview,
    pendingSubmission,
    submitting,
    resolvingSubmission,
    submissionUnknown,
    submissionError,
    lastOrder,
    activeOwnerId,
    originalAmount,
    readyForPreview,
    addresses,
    cart,
    load,
    selectAddress,
    toggleBenefit,
    refreshPreview,
    refreshAuthority,
    recoverPendingSubmission,
    submitOrder,
  };
});
