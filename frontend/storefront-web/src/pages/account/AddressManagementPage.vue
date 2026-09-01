<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import type { Address, AddressInput, BusinessId } from "@plain-journal/foundation";

import {
  AddressAccessChangedError,
  type AddressAccessContext,
  useAddressStore,
} from "../../entities/address";
import { useSessionStore } from "../../features/customer-session";

const addresses = useAddressStore();
const session = useSessionStore();
const editingId = ref<BusinessId | null>(null);
const pendingDeleteId = ref<BusinessId | null>(null);
const feedback = ref<string | null>(null);
const accessContext = computed<AddressAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));
const addressErrorTitle = computed(() => {
  if (addresses.errorTone === "unknown") {
    return "地址操作结果待确认";
  }
  if (addresses.errorTone === "attention") {
    return "地址已修改，列表需要重新核对";
  }
  return "收货信息未完成";
});

function emptyForm(): AddressInput {
  return {
    recipientName: "",
    phone: "",
    province: "",
    provinceCode: "",
    city: "",
    cityCode: "",
    district: "",
    districtCode: "",
    detailAddress: "",
    postalCode: null,
    setDefault: false,
  };
}

const form = reactive<AddressInput>(emptyForm());

function resetForm() {
  Object.assign(form, emptyForm());
  editingId.value = null;
}

let renderedOwnerId: BusinessId | null = null;
watch(accessContext, async (context) => {
  if (renderedOwnerId !== context.ownerId) {
    resetForm();
    pendingDeleteId.value = null;
    feedback.value = null;
  }
  renderedOwnerId = context.ownerId;
  try {
    await addresses.load(context);
  } catch (cause) {
    if (!(cause instanceof AddressAccessChangedError)) {
      // The entity store exposes the current owner's actionable error state.
    }
  }
}, { immediate: true });

function edit(address: Address) {
  editingId.value = address.id;
  Object.assign(form, {
    recipientName: address.recipientName,
    phone: address.phone,
    province: address.province,
    provinceCode: address.provinceCode,
    city: address.city,
    cityCode: address.cityCode,
    district: address.district,
    districtCode: address.districtCode,
    detailAddress: address.detailAddress,
    postalCode: address.postalCode,
    setDefault: address.defaultAddress,
  });
  feedback.value = null;
  document.querySelector<HTMLHeadingElement>("#address-form-title")?.focus();
}

async function save() {
  feedback.value = null;
  try {
    if (editingId.value) {
      await addresses.update(accessContext.value, editingId.value, { ...form });
      feedback.value = "地址修改已确认。";
    } else {
      await addresses.create(accessContext.value, { ...form });
      feedback.value = "新收货地址已确认。";
    }
    resetForm();
  } catch {
    // Keep the form intact so the user can correct or retry it.
  }
}

async function reloadAddresses() {
  feedback.value = null;
  try {
    await addresses.load(accessContext.value);
    feedback.value = "最新地址列表已重新读取，请核对当前事实。";
  } catch (cause) {
    if (!(cause instanceof AddressAccessChangedError)) {
      // The entity store keeps the current owner's read failure.
    }
  }
}

async function makeDefault(addressId: BusinessId) {
  feedback.value = null;
  try {
    await addresses.setDefault(accessContext.value, addressId);
    feedback.value = "默认收货地址已更新。";
  } catch {
    // The store exposes the server error without claiming success.
  }
}

async function remove(addressId: BusinessId) {
  feedback.value = null;
  try {
    await addresses.remove(accessContext.value, addressId);
    pendingDeleteId.value = null;
    if (editingId.value === addressId) {
      resetForm();
    }
    feedback.value = "地址已删除；如删除的是默认地址，服务端已选择新的默认地址。";
  } catch {
    // The confirmation remains open when the result is not confirmed.
  }
}
</script>

<template>
  <PjPageContainer as="section" size="wide" class="address-page">
    <nav class="address-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>收货信息</span>
    </nav>

    <header class="address-intro">
      <div>
        <p class="address-context">账户事实</p>
        <h1>收货信息</h1>
      </div>
      <p>最多保存 20 个地址</p>
    </header>

    <PjStatusNotice
      v-if="feedback"
      class="address-feedback"
      tone="success"
      title="地址事实已更新"
    >
      <p>{{ feedback }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-if="addresses.error"
      class="address-error"
      :tone="addresses.errorTone ?? 'danger'"
      :title="addressErrorTitle"
      :assertive="addresses.errorTone === 'danger'"
    >
      <p>{{ addresses.error }}</p>
      <template #actions>
        <PjButton variant="text" @click="reloadAddresses">重新读取地址</PjButton>
      </template>
    </PjStatusNotice>

    <section class="address-layout">
      <section class="address-list" aria-labelledby="saved-addresses-title">
        <header class="address-section-heading">
          <p class="address-context">已保存</p>
          <h2 id="saved-addresses-title">选择与管理地址</h2>
        </header>

        <PjStatusNotice
          v-if="addresses.loading"
          tone="processing"
          title="正在读取收货地址"
        >
          <p>只会展示当前账户由服务端返回的地址事实。</p>
        </PjStatusNotice>
        <PjStatusNotice
          v-else-if="addresses.addresses.length === 0"
          tone="neutral"
          title="还没有收货地址"
        >
          <p>还没有收货地址。第一个地址会自动成为默认地址。</p>
        </PjStatusNotice>

        <article
          v-for="address in addresses.addresses"
          :key="address.id"
          class="address-row"
        >
          <div>
            <p class="address-row__name">
              <strong>{{ address.recipientName }}</strong>
              <span v-if="address.defaultAddress">默认地址</span>
            </p>
            <address>
              {{ address.province }} {{ address.city }} {{ address.district }}
              {{ address.detailAddress }}<br />
              {{ address.phone }}
              <template v-if="address.postalCode"> · {{ address.postalCode }}</template>
            </address>
          </div>
          <PjActionGroup class="address-row__actions" :stack-on-compact="false">
            <PjButton
              v-if="!address.defaultAddress"
              variant="text"
              :disabled="addresses.saving"
              @click="makeDefault(address.id)"
            >
              设为默认地址
            </PjButton>
            <PjButton variant="text" @click="edit(address)">
              修改
            </PjButton>
            <PjButton
              variant="text"
              class="address-delete-action"
              @click="pendingDeleteId = address.id"
            >
              删除
            </PjButton>
          </PjActionGroup>
          <div
            v-if="pendingDeleteId === address.id"
            class="address-delete-confirmation"
            role="group"
            :aria-label="`删除 ${address.recipientName} 的地址`"
          >
            <PjStatusNotice
              tone="danger"
              title="确认删除这个地址"
            >
              <p>删除后无法从当前页面撤销。确定不再使用这个地址吗？</p>
              <template #actions>
                <PjActionGroup>
                  <PjButton
                    variant="destructive"
                    :loading="addresses.saving"
                    @click="remove(address.id)"
                  >
                    {{ addresses.saving ? "正在删除…" : "删除这个地址" }}
                  </PjButton>
                  <PjButton variant="text" @click="pendingDeleteId = null">
                    保留地址
                  </PjButton>
                </PjActionGroup>
              </template>
            </PjStatusNotice>
          </div>
        </article>
      </section>

      <PjSurface
        as="form"
        tone="plain"
        padding="medium"
        class="address-form"
        @submit.prevent="save"
      >
        <header class="address-section-heading">
          <p class="address-context">{{ editingId ? "修改地址" : "新增地址" }}</p>
          <h2 id="address-form-title" tabindex="-1">
            {{ editingId ? "核对修改后的收货信息" : "填写新的收货信息" }}
          </h2>
        </header>

        <PjField v-slot="{ describedBy, invalid }" label="收货人" for-id="recipient-name" required>
          <input
            id="recipient-name"
            v-model.trim="form.recipientName"
            class="pj-control"
            required
            maxlength="60"
            autocomplete="name"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
          />
        </PjField>
        <PjField v-slot="{ describedBy, invalid }" label="联系电话" for-id="address-phone" required>
          <input
            id="address-phone"
            v-model.trim="form.phone"
            class="pj-control"
            required
            maxlength="30"
            pattern="\+?[0-9 \-]{6,29}"
            autocomplete="tel"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
          />
        </PjField>

        <div class="address-form__region">
          <PjField v-slot="{ describedBy, invalid }" label="省份" for-id="province" required>
            <input
              id="province"
              v-model.trim="form.province"
              class="pj-control"
              required
              maxlength="60"
              autocomplete="address-level1"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField v-slot="{ describedBy, invalid }" label="省级代码" for-id="province-code" required>
            <input
              id="province-code"
              v-model.trim="form.provinceCode"
              class="pj-control"
              required
              pattern="\d{6}"
              inputmode="numeric"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField v-slot="{ describedBy, invalid }" label="城市" for-id="city" required>
            <input
              id="city"
              v-model.trim="form.city"
              class="pj-control"
              required
              maxlength="60"
              autocomplete="address-level2"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField v-slot="{ describedBy, invalid }" label="市级代码" for-id="city-code" required>
            <input
              id="city-code"
              v-model.trim="form.cityCode"
              class="pj-control"
              required
              pattern="\d{6}"
              inputmode="numeric"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField v-slot="{ describedBy, invalid }" label="区县" for-id="district" required>
            <input
              id="district"
              v-model.trim="form.district"
              class="pj-control"
              required
              maxlength="60"
              autocomplete="address-level3"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField v-slot="{ describedBy, invalid }" label="区县代码" for-id="district-code" required>
            <input
              id="district-code"
              v-model.trim="form.districtCode"
              class="pj-control"
              required
              pattern="\d{6}"
              inputmode="numeric"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
        </div>

        <PjField v-slot="{ describedBy, invalid }" label="详细地址" for-id="detail-address" required>
          <textarea
            id="detail-address"
            v-model.trim="form.detailAddress"
            class="pj-control"
            required
            maxlength="240"
            rows="3"
            autocomplete="street-address"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
          />
        </PjField>
        <PjField v-slot="{ describedBy, invalid }" label="邮政编码（可选）" for-id="postal-code">
          <input
            id="postal-code"
            v-model.trim="form.postalCode"
            class="pj-control"
            maxlength="20"
            pattern="[A-Za-z0-9 \-]*"
            autocomplete="postal-code"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
          />
        </PjField>
        <label class="address-default-option">
          <input v-model="form.setDefault" type="checkbox" />
          保存后作为默认收货地址
        </label>

        <PjActionGroup>
          <PjButton type="submit" :loading="addresses.saving">
            {{
              addresses.saving
                ? "正在等待服务端确认…"
                : editingId
                  ? "保存地址修改"
                  : "保存新收货地址"
            }}
          </PjButton>
          <PjButton v-if="editingId" variant="text" @click="resetForm">
            放弃修改
          </PjButton>
        </PjActionGroup>
      </PjSurface>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.address-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.address-path {
  display: flex;
  gap: var(--pj-space-2);
  margin-bottom: var(--pj-space-7);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.address-intro {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.address-intro h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: var(--pj-line-height-tight);
}

.address-intro > p,
.address-context {
  color: var(--pj-text-secondary);
}

.address-context {
  margin: 0 0 var(--pj-space-3);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.address-feedback,
.address-error {
  margin-bottom: var(--pj-space-5);
}

.address-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(22rem, 0.85fr);
  gap: clamp(2rem, 5vw, 6rem);
  align-items: start;
}

.address-list,
.address-form {
  min-width: 0;
}

.address-section-heading {
  max-width: var(--pj-layout-reading);
  margin-bottom: var(--pj-space-6);
}

.address-section-heading h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 550;
  line-height: var(--pj-line-height-tight);
}

.address-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.address-row:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.address-row__name {
  display: flex;
  align-items: center;
  gap: var(--pj-space-3);
  margin: 0 0 var(--pj-space-2);
}

.address-row__name span {
  color: var(--pj-brand-primary-hover);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.06em;
}

.address-row address {
  color: var(--pj-text-secondary);
  font-style: normal;
  line-height: var(--pj-line-height-normal);
}

.address-row__actions {
  min-width: 8rem;
  align-items: flex-end;
  flex-direction: column;
}

.address-delete-action {
  color: var(--pj-status-danger-text);
}

.address-delete-confirmation {
  grid-column: 1 / -1;
}

.address-form {
  position: sticky;
  top: var(--pj-space-5);
  display: grid;
  gap: var(--pj-space-4);
}

.address-form .address-section-heading {
  margin-bottom: var(--pj-space-2);
}

.address-form textarea {
  min-height: 6rem;
  resize: vertical;
}

.address-form__region {
  display: grid;
  grid-template-columns: 1fr 0.7fr;
  gap: var(--pj-space-4);
}

.address-default-option {
  display: flex;
  align-items: center;
  gap: var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

@media (max-width: 64rem) {
  .address-layout {
    grid-template-columns: minmax(0, 1fr) minmax(20rem, 0.75fr);
    gap: var(--pj-space-6);
  }
}

@media (max-width: 48rem) {
  .address-intro {
    align-items: flex-start;
    flex-direction: column;
  }

  .address-form {
    position: static;
  }

  .address-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 32rem) {
  .address-row {
    grid-template-columns: 1fr;
  }

  .address-row__actions {
    min-width: 0;
    align-items: flex-start;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .address-form__region {
    grid-template-columns: 1fr;
  }
}
</style>
