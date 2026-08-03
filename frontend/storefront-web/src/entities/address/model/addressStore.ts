import { computed, ref } from "vue";
import { defineStore } from "pinia";

import {
  ApiError,
  createApiClient,
  createIdentityApi,
  type Address,
  type AddressInput,
  type BusinessId,
  type IdentityApi,
} from "@plain-journal/foundation";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim() ?? "";

export type AddressErrorTone = "danger" | "unknown" | "attention";

export interface AddressAccessContext {
  authenticated: boolean;
  ownerId: BusinessId | null;
  accessToken: string | null;
}

interface ActiveAddressAccess {
  ownerId: BusinessId;
  accessToken: string;
  revision: number;
}

export class AddressAccessChangedError extends Error {
  constructor() {
    super("账户或会话已切换，旧的地址请求结果不会写入当前页面。");
    this.name = "AddressAccessChangedError";
  }
}

function isActiveContext(context: AddressAccessContext): context is {
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

function identityApi(accessToken: string): IdentityApi {
  return createIdentityApi(createApiClient({
    baseUrl: apiBaseUrl,
    timeoutMs: 8000,
    tokenProvider: () => accessToken,
  }));
}

export const useAddressStore = defineStore("customer-addresses", () => {
  const addresses = ref<Address[]>([]);
  const loading = ref(false);
  const saving = ref(false);
  const error = ref<string | null>(null);
  const errorTone = ref<AddressErrorTone | null>(null);
  const activeOwnerId = ref<BusinessId | null>(null);
  let activeAccessToken: string | null = null;
  let accessRevision = 0;
  let loadRevision = 0;
  let saveRevision = 0;

  const defaultAddress = computed(() =>
    addresses.value.find((address) => address.defaultAddress) ?? null);

  function synchronizeAccess(context: AddressAccessContext): ActiveAddressAccess | null {
    const nextOwnerId = isActiveContext(context) ? context.ownerId : null;
    const nextAccessToken = isActiveContext(context) ? context.accessToken : null;
    const ownerChanged = activeOwnerId.value !== nextOwnerId;
    const accessChanged = ownerChanged || activeAccessToken !== nextAccessToken;

    if (accessChanged) {
      activeOwnerId.value = nextOwnerId;
      activeAccessToken = nextAccessToken;
      accessRevision += 1;
      loadRevision += 1;
      saveRevision += 1;
      loading.value = false;
      saving.value = false;
      error.value = null;
      errorTone.value = null;
      if (ownerChanged) {
        addresses.value = [];
      }
    }

    if (!isActiveContext(context)) {
      addresses.value = [];
      return null;
    }
    return {
      ownerId: context.ownerId,
      accessToken: context.accessToken,
      revision: accessRevision,
    };
  }

  function accessIsCurrent(access: ActiveAddressAccess): boolean {
    return access.revision === accessRevision
      && access.ownerId === activeOwnerId.value
      && access.accessToken === activeAccessToken;
  }

  function requireCurrent(access: ActiveAddressAccess) {
    if (!accessIsCurrent(access)) {
      throw new AddressAccessChangedError();
    }
  }

  async function load(context: AddressAccessContext): Promise<void> {
    const access = synchronizeAccess(context);
    if (!access) {
      return;
    }

    const requestRevision = ++loadRevision;
    loading.value = true;
    error.value = null;
    errorTone.value = null;
    try {
      const result = await identityApi(access.accessToken).addresses();
      requireCurrent(access);
      if (requestRevision !== loadRevision) {
        throw new AddressAccessChangedError();
      }
      addresses.value = result;
    } catch (cause) {
      if (!accessIsCurrent(access) || requestRevision !== loadRevision) {
        throw new AddressAccessChangedError();
      }
      error.value = cause instanceof Error ? cause.message : "收货地址暂时无法读取。";
      errorTone.value = "danger";
      throw cause;
    } finally {
      if (accessIsCurrent(access) && requestRevision === loadRevision) {
        loading.value = false;
      }
    }
  }

  async function create(context: AddressAccessContext, input: AddressInput) {
    return mutate(
      context,
      (api) => api.createAddress(input),
      "地址修改未完成。",
    );
  }

  async function update(
    context: AddressAccessContext,
    addressId: BusinessId,
    input: AddressInput,
  ) {
    return mutate(
      context,
      (api) => api.updateAddress(addressId, input),
      "地址修改未完成。",
    );
  }

  async function setDefault(context: AddressAccessContext, addressId: BusinessId) {
    return mutate(
      context,
      (api) => api.setDefaultAddress(addressId),
      "默认地址修改未完成。",
    );
  }

  async function remove(context: AddressAccessContext, addressId: BusinessId) {
    return mutate(
      context,
      async (api) => {
        await api.deleteAddress(addressId);
      },
      "地址删除结果未确认。",
    );
  }

  async function mutate<Result>(
    context: AddressAccessContext,
    operation: (api: IdentityApi) => Promise<Result>,
    failureMessage: string,
  ): Promise<Result> {
    const access = synchronizeAccess(context);
    if (!access) {
      const cause = new Error("当前会话没有可用于管理地址的账户事实。");
      error.value = cause.message;
      throw cause;
    }

    const requestRevision = ++saveRevision;
    saving.value = true;
    error.value = null;
    errorTone.value = null;
    try {
      const result = await operation(identityApi(access.accessToken));
      requireCurrent(access);

      try {
        await load(context);
      } catch (cause) {
        if (cause instanceof AddressAccessChangedError) {
          throw cause;
        }
        requireCurrent(access);
        error.value = "Identity 已确认地址修改，但最新列表未能重新读取。请先刷新，勿重复提交。";
        errorTone.value = "attention";
      }
      requireCurrent(access);
      return result;
    } catch (cause) {
      if (!accessIsCurrent(access)) {
        throw new AddressAccessChangedError();
      }
      if (!(cause instanceof AddressAccessChangedError) && !error.value) {
        if (mutationResultMayBeUnknown(cause)) {
          error.value = "地址操作结果暂时未知。请先重新读取地址，核对事实后再决定是否重试。";
          errorTone.value = "unknown";
        } else {
          error.value = cause instanceof Error ? cause.message : failureMessage;
          errorTone.value = "danger";
        }
      }
      throw cause;
    } finally {
      if (accessIsCurrent(access) && requestRevision === saveRevision) {
        saving.value = false;
      }
    }
  }

  return {
    addresses,
    loading,
    saving,
    error,
    errorTone,
    activeOwnerId,
    defaultAddress,
    load,
    create,
    update,
    setDefault,
    remove,
  };
});

function mutationResultMayBeUnknown(cause: unknown): boolean {
  return cause instanceof ApiError && (
    cause.kind === "network"
    || cause.kind === "timeout"
    || cause.kind === "invalid-response"
    || (cause.kind === "http" && (cause.status ?? 500) >= 500)
  );
}
