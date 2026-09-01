<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";

import {
  formatMoney,
  type CartItem,
} from "@plain-journal/foundation";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  AccountCartAccessChangedError,
  AccountCartItemRow,
  AccountCartMutationBusyError,
  type AccountCartAccessContext,
  useAccountCartStore,
} from "../../entities/account-cart";
import {
  GuestBagItemRow,
  useBagStore,
  type GuestBagItem,
} from "../../entities/guest-bag";
import { useSessionStore } from "../../features/customer-session";
import { AsyncState } from "../../shared/ui";

const bag = useBagStore();
const session = useSessionStore();
const accountCart = useAccountCartStore();
const removed = ref<GuestBagItem | null>(null);
const accountAccess = computed<AccountCartAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const visibleItemCount = computed(() => (
  session.authenticated ? accountCart.itemCount : bag.itemCount
));
const mutationTone = computed<
  "neutral" | "success" | "danger" | "processing" | "unknown"
>(() => {
  switch (accountCart.mutationStatus) {
    case "pending":
      return "processing";
    case "succeeded":
      return "success";
    case "unknown":
      return "unknown";
    case "failed":
      return "danger";
    default:
      return "neutral";
  }
});
const mutationTitle = computed(() => {
  switch (accountCart.mutationStatus) {
    case "pending":
      return "正在确认账户购物车";
    case "succeeded":
      return "账户购物车已更新";
    case "unknown":
      return "购物车结果尚未确认";
    case "failed":
      return "购物车修改未完成";
    default:
      return "账户购物车";
  }
});
const mergeTone = computed<
  "warning" | "danger" | "processing" | "unknown" | "attention"
>(() => {
  switch (session.bagMergeStatus) {
    case "pending":
      return "processing";
    case "failed":
      return "danger";
    case "ownership-conflict":
      return "attention";
    case "unknown":
      return "unknown";
    default:
      return "warning";
  }
});

watch(
  () => [
    session.authenticated,
    session.profile?.id ?? null,
    session.accessToken,
  ] as const,
  async () => {
    removed.value = null;
    await loadAccountCart();
  },
  { immediate: true },
);

async function loadAccountCart(force = false) {
  try {
    await accountCart.load(accountAccess.value, { force });
  } catch (cause) {
    if (
      cause instanceof AccountCartAccessChangedError
      || cause instanceof AccountCartMutationBusyError
    ) {
      return;
    }
    // The entity owns the factual, retryable read error.
  }
}

function removeGuestItem(skuId: string) {
  removed.value = bag.removeItem(skuId);
}

function undoGuestRemoval() {
  if (removed.value) {
    bag.restoreItem(removed.value);
    removed.value = null;
  }
}

async function retryMerge() {
  await session.mergeGuestBag();
  await loadAccountCart(true);
}

async function updateAccountItem(
  item: CartItem,
  input: { quantity: number; selected: boolean },
) {
  try {
    await accountCart.updateItem(accountAccess.value, item, input);
  } catch (cause) {
    if (cause instanceof AccountCartAccessChangedError) {
      return;
    }
    // The entity distinguishes a known rejection from an unknown result.
  }
}

async function updateAccountQuantity(item: CartItem, quantity: number) {
  await updateAccountItem(item, {
    quantity,
    selected: item.selected,
  });
}

async function updateAccountSelection(item: CartItem, selected: boolean) {
  await updateAccountItem(item, {
    quantity: item.quantity,
    selected,
  });
}

async function removeAccountItem(item: CartItem) {
  try {
    await accountCart.removeItem(accountAccess.value, item);
  } catch (cause) {
    if (cause instanceof AccountCartAccessChangedError) {
      return;
    }
    // Unknown transport results remain visible until an explicit reload.
  }
}
</script>

<template>
  <PjPageContainer as="section" class="bag-page-shell">
    <nav class="content-path" aria-label="当前位置">
      <RouterLink to="/">素简记</RouterLink>
      <span aria-hidden="true">/</span>
      <span>购物袋</span>
    </nav>

    <header class="bag-intro">
      <div class="bag-intro__copy">
        <p>{{ session.authenticated ? "账户购物袋" : "当前设备" }}</p>
        <h1>购物袋</h1>
        <p>
          {{
            session.authenticated
              ? "先确认要结算的商品；价格、库存与优惠会在下一步重新读取。"
              : "商品只保存在当前设备；登录并收到服务端确认后才会移入账户。"
          }}
        </p>
      </div>
      <p class="bag-intro__count" aria-live="polite">
        <strong>{{ visibleItemCount }}</strong>
        <span>件商品</span>
      </p>
    </header>

    <template v-if="session.authenticated">
      <AsyncState
        v-if="accountCart.loading && accountCart.items.length === 0"
        loading
        loading-message="正在读取账户购物车…"
      />

      <PjStatusNotice
        v-if="accountCart.error"
        class="bag-notice"
        tone="danger"
        title="账户购物车未读取"
        assertive
      >
        <p>{{ accountCart.error }}</p>
        <template #actions>
          <PjButton
            variant="text"
            :disabled="accountCart.loading || accountCart.mutating"
            @click="loadAccountCart(true)"
          >
            重新读取 Trade 事实
          </PjButton>
        </template>
      </PjStatusNotice>

      <PjStatusNotice
        v-if="accountCart.mutationMessage"
        class="bag-notice"
        :tone="mutationTone"
        :title="mutationTitle"
        :assertive="accountCart.mutationStatus === 'failed'"
      >
        <p>{{ accountCart.mutationMessage }}</p>
        <template v-if="accountCart.mutationStatus === 'unknown'" #actions>
          <PjButton
            variant="text"
            :disabled="accountCart.loading || accountCart.mutating"
            @click="loadAccountCart(true)"
          >
            先重新读取
          </PjButton>
        </template>
      </PjStatusNotice>

      <PjSurface
        v-if="
          !accountCart.loading
          && !accountCart.error
          && accountCart.items.length === 0
          && bag.items.length === 0
        "
        class="bag-empty"
        tone="soft"
        padding="large"
      >
        <p>购物袋为空</p>
        <h2>先找到一件真正适合的商品。</h2>
        <RouterLink class="primary-action" to="/products">查看全部商品 →</RouterLink>
      </PjSurface>

      <template v-else>
        <div
          v-if="accountCart.items.length > 0"
          class="bag-items"
          aria-label="账户购物袋商品"
        >
          <AccountCartItemRow
            v-for="item in accountCart.items"
            :key="`${accountCart.activeOwnerId}:${item.id}`"
            :item="item"
            :busy="accountCart.mutating"
            @quantity-change="updateAccountQuantity"
            @selection-change="updateAccountSelection"
            @remove="removeAccountItem"
          />
        </div>

        <PjStatusNotice
          v-if="bag.items.length > 0"
          class="bag-device-pending"
          :tone="mergeTone"
        >
          <div class="bag-device-pending__header">
            <div>
              <p>当前设备仍保留</p>
              <h2>尚未确认移除的游客商品</h2>
            </div>
            <strong>{{ bag.itemCount }} 件</strong>
          </div>
          <p>
            这些商品不计入上方账户小计。若此前合并响应丢失，Trade 读取可能已经包含它们；
            使用原重试键重试不会重复累加。
          </p>
          <p v-if="session.bagMergeMessage" role="status" aria-live="polite">
            {{ session.bagMergeMessage }}
          </p>
          <template #actions>
            <PjButton
              variant="text"
              :loading="session.bagMergeStatus === 'pending'"
              @click="retryMerge"
            >
              {{
                session.bagMergeStatus === "pending"
                  ? "正在使用原重试键确认…"
                  : "使用原重试键再次确认"
              }}
            </PjButton>
          </template>
        </PjStatusNotice>

        <PjSurface
          v-if="accountCart.items.length > 0"
          as="aside"
          class="bag-summary"
          tone="soft"
          padding="large"
          aria-labelledby="bag-summary-title"
        >
          <p>结算摘要</p>
          <h2 id="bag-summary-title">当前已选商品</h2>
          <div class="bag-summary__amount">
            <p>已选商品小计</p>
            <strong>{{ formatMoney(accountCart.selectedSubtotal) }}</strong>
          </div>
          <p>
            已选 {{ accountCart.selectedItemCount }} 件。此金额来自 Trade 保存的商品快照；
            结算草稿会调用 Marketing 重新试算，提交前仍会重新校验当前价格和库存。
          </p>
          <RouterLink
            v-if="accountCart.selectedItems.length > 0"
            class="primary-action bag-login-action"
            to="/checkout"
          >
            查看结算草稿 →
          </RouterLink>
          <p v-else class="purchase-note">至少选择一件商品后才能进入结算草稿。</p>
        </PjSurface>
      </template>
    </template>

    <template v-else>
      <PjSurface
        v-if="bag.items.length === 0"
        class="bag-empty"
        tone="soft"
        padding="large"
      >
        <p>购物袋为空</p>
        <h2>先找到一件真正适合的商品。</h2>
        <RouterLink class="primary-action" to="/products">查看全部商品 →</RouterLink>
      </PjSurface>

      <template v-else>
        <div class="bag-items" aria-label="当前设备购物袋商品">
          <GuestBagItemRow
            v-for="item in bag.items"
            :key="item.skuId"
            :item="item"
            @quantity-change="bag.updateQuantity"
            @remove="removeGuestItem"
          />
        </div>

        <PjSurface
          as="aside"
          class="bag-summary"
          tone="soft"
          padding="large"
          aria-labelledby="guest-bag-summary-title"
        >
          <p>结算摘要</p>
          <h2 id="guest-bag-summary-title">当前设备商品</h2>
          <div class="bag-summary__amount">
            <p>商品小计</p>
            <strong>{{ formatMoney(bag.subtotal) }}</strong>
          </div>
          <p>
            购物袋不代表库存已锁定。登录后会使用稳定重试键合并到账户，
            不覆盖账户中已有的同款商品。
          </p>
          <RouterLink
            class="primary-action bag-login-action"
            :to="{ name: 'login', query: { returnTo: '/bag' } }"
          >
            登录并安全合并 →
          </RouterLink>
          <p class="purchase-note">
            未收到服务端合并成功前，商品会继续保留在此设备。
          </p>
        </PjSurface>
      </template>

      <PjStatusNotice
        v-if="removed"
        class="bag-notice"
        tone="success"
        :title="`已移出 ${removed.productTitle}`"
      >
        <p>商品仍可恢复到当前设备。</p>
        <template #actions>
          <PjButton variant="text" @click="undoGuestRemoval">撤销</PjButton>
        </template>
      </PjStatusNotice>
    </template>
  </PjPageContainer>
</template>

<style scoped>
.bag-page-shell {
  padding-block: var(--pj-space-6) var(--pj-space-8);
}

.bag-intro {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--pj-space-7);
  margin-bottom: var(--pj-space-7);
}

.bag-intro__copy {
  max-width: var(--pj-layout-reading);
}

.bag-intro__copy > p:first-child,
.bag-empty > p:first-child,
.bag-summary > p:first-child,
.bag-device-pending__header p {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.bag-intro h1 {
  margin: 0;
  font-size: clamp(2.4rem, 5vw, 5rem);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: 0.98;
}

.bag-intro__copy > p:last-child {
  max-width: 40rem;
  margin: var(--pj-space-4) 0 0;
  color: var(--pj-text-secondary);
}

.bag-intro__count {
  display: grid;
  justify-items: end;
  gap: var(--pj-space-1);
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.bag-intro__count strong {
  color: var(--pj-text-primary);
  font-size: clamp(1.8rem, 3vw, 3rem);
  font-weight: 520;
  line-height: 1;
}

.bag-items {
  border-top: 1px solid var(--pj-border-strong);
}

.bag-notice,
.bag-device-pending {
  margin-bottom: var(--pj-space-5);
}

.bag-empty {
  max-width: var(--pj-layout-reading);
}

.bag-empty h2,
.bag-summary h2,
.bag-device-pending h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 550;
}

.bag-empty h2 {
  margin-bottom: var(--pj-space-5);
}

.bag-device-pending {
  margin-top: var(--pj-space-6);
}

.bag-device-pending__header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.bag-device-pending__header p {
  margin-bottom: var(--pj-space-1);
}

.bag-summary {
  width: min(100%, 28rem);
  margin: var(--pj-space-7) 0 0 auto;
}

.bag-summary__amount {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-top: var(--pj-space-5);
  font-size: var(--pj-font-size-lg);
}

.bag-summary__amount p {
  margin: 0;
}

.bag-summary > p:not(:first-child),
.purchase-note {
  color: var(--pj-text-secondary);
}

.bag-login-action {
  width: 100%;
}

@media (max-width: 48rem) {
  .bag-intro {
    grid-template-columns: 1fr;
    align-items: start;
    gap: var(--pj-space-4);
  }

  .bag-intro__count {
    grid-template-columns: auto auto 1fr;
    justify-items: start;
    align-items: baseline;
  }

  .bag-summary {
    width: 100%;
  }
}

@media (max-width: 32rem) {
  .bag-device-pending__header,
  .bag-summary__amount {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
