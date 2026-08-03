import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  createApiClient,
  createMarketingApi,
  type Benefit,
  type BusinessId,
  type MarketingApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export interface BenefitAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveBenefitAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export class BenefitAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的权益请求结果不会写入当前页面。");
    this.name = "BenefitAccessChangedError";
  }
}

export class BenefitOwnershipMismatchError extends Error {
  constructor() {
    super("权益响应包含不属于当前账户的事实，页面已拒绝展示。");
    this.name = "BenefitOwnershipMismatchError";
  }
}

function isActiveContext(context: BenefitAccessContext): context is {
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

function marketingApi(accessToken: string): MarketingApi {
  return createMarketingApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

export const useBenefitsStore = defineStore("customer-benefits", () => {
  const benefits = ref<Benefit[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let loadRevision = 0;

  const availableCount = computed(() =>
    benefits.value.filter((benefit) => benefit.status === "AVAILABLE").length);

  function synchronizeAccess(
    context: BenefitAccessContext,
  ): ActiveBenefitAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const accessChanged = ownerChanged || activeAccessToken !== nextAccessToken;

    if (accessChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      loadRevision += 1;
      loading.value = false;
      error.value = null;
      if (ownerChanged) {
        benefits.value = [];
      }
    }

    if (!isActiveContext(context)) {
      benefits.value = [];
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveBenefitAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveBenefitAccess) {
    if (!accessIsCurrent(access)) {
      throw new BenefitAccessChangedError();
    }
  }

  async function load(context: BenefitAccessContext): Promise<Benefit[]> {
    const access = synchronizeAccess(context);
    if (!access) {
      return [];
    }

    const requestRevision = ++loadRevision;
    loading.value = true;
    error.value = null;
    try {
      const result = await marketingApi(access.accessToken).benefits();
      requireCurrent(access);
      if (requestRevision !== loadRevision) {
        throw new BenefitAccessChangedError();
      }
      if (result.some((benefit) => benefit.userId !== access.ownerId)) {
        throw new BenefitOwnershipMismatchError();
      }
      benefits.value = result;
      return benefits.value;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== loadRevision) {
        throw new BenefitAccessChangedError();
      }
      error.value = cause instanceof Error ? cause.message : "权益事实暂时无法读取。";
      return benefits.value;
    } finally {
      if (accessIsCurrent(access) && requestRevision === loadRevision) {
        loading.value = false;
      }
    }
  }

  return {
    benefits,
    loading,
    error,
    activeOwnerId,
    availableCount,
    load,
  };
});
