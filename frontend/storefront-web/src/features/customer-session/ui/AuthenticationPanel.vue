<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import {
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import { useSessionStore } from "../model/session";

const props = defineProps<{
  mode: "login" | "register";
  returnTo: string;
}>();

const emit = defineEmits<{
  authenticated: [];
}>();

const session = useSessionStore();
const displayName = ref("");
const email = ref("");
const password = ref("");
const registering = computed(() => props.mode === "register");
const eyebrow = computed(() => registering.value ? "新账户" : "账户");
const heading = computed(() => registering.value
  ? "只留下必要的信息。"
  : "继续你的素简记。");
const lead = computed(() => registering.value
  ? "密码至少 10 位，并同时包含字母和数字。注册成功后会立即建立会话。"
  : "登录后会恢复账户会话，并尝试把当前设备购物袋安全合并到账户。");
const submitLabel = computed(() => {
  if (session.busy) {
    return registering.value ? "正在创建…" : "正在确认…";
  }
  return registering.value ? "创建账户 →" : "登录 →";
});
const alternateRoute = computed(() => registering.value ? "login" : "register");

async function submit() {
  try {
    if (registering.value) {
      await session.registerAndLogin({
        displayName: displayName.value,
        email: email.value,
        password: password.value,
      });
    } else {
      await session.login({
        email: email.value,
        password: password.value,
      });
    }
    emit("authenticated");
  } catch {
    // The session feature keeps the explicit backend or transport failure.
  }
}
</script>

<template>
  <PjPageContainer as="section" size="reading" class="identity-page">
    <header class="identity-intro">
      <p class="identity-context">{{ eyebrow }}</p>
      <h1>{{ heading }}</h1>
      <p class="identity-lead">{{ lead }}</p>
    </header>

    <PjSurface as="div" tone="plain" padding="medium" class="identity-panel">
      <form class="identity-form" @submit.prevent="submit">
        <PjField
          v-if="registering"
          v-slot="{ describedBy, invalid }"
          label="称呼"
          for-id="display-name"
          required
        >
          <input
            id="display-name"
            v-model.trim="displayName"
            class="pj-control"
            type="text"
            autocomplete="name"
            minlength="2"
            maxlength="50"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
            required
          />
        </PjField>
        <PjField v-slot="{ describedBy, invalid }" label="邮箱" for-id="email" required>
          <input
            id="email"
            v-model.trim="email"
            class="pj-control"
            type="email"
            autocomplete="email"
            maxlength="190"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
            required
          />
        </PjField>
        <PjField
          v-slot="{ describedBy, invalid }"
          label="密码"
          for-id="password"
          :hint="registering ? '至少 10 位，并同时包含字母和数字。' : '使用当前账户密码继续。'"
          required
        >
          <input
            id="password"
            v-model="password"
            class="pj-control"
            type="password"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            :minlength="registering ? 10 : undefined"
            :maxlength="registering ? 64 : 128"
            :pattern="registering ? '(?=.*[A-Za-z])(?=.*\\d).+' : undefined"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
            required
          />
        </PjField>
        <PjStatusNotice
          v-if="session.error"
          tone="danger"
          assertive
          :title="registering ? '账户未创建' : '登录未完成'"
        >
          <p>{{ session.error }}</p>
        </PjStatusNotice>
        <PjButton type="submit" :loading="session.busy" block>
          {{ submitLabel }}
        </PjButton>
      </form>

      <p class="identity-switch">
        {{ registering ? "已有账户？" : "还没有账户？" }}
        <RouterLink :to="{ name: alternateRoute, query: { returnTo } }">
          {{ registering ? "登录" : "注册" }}
        </RouterLink>
      </p>
    </PjSurface>
  </PjPageContainer>
</template>

<style scoped>
.identity-page {
  display: grid;
  min-height: calc(100vh - 10rem);
  align-content: center;
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-8);
}

.identity-intro {
  max-width: 38rem;
}

.identity-context {
  margin: 0 0 var(--pj-space-3);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.identity-intro h1 {
  margin: 0;
  max-width: 12ch;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: var(--pj-letter-spacing-page-title);
  line-height: var(--pj-line-height-tight);
}

.identity-lead,
.identity-switch {
  color: var(--pj-text-secondary);
}

.identity-lead {
  max-width: 36rem;
  margin: var(--pj-space-4) 0 0;
}

.identity-form {
  display: grid;
  gap: var(--pj-space-5);
}

.identity-switch {
  margin-top: var(--pj-space-6);
}

.identity-switch a {
  color: var(--pj-brand-primary-hover);
  font-weight: 650;
}

@media (max-width: 32rem) {
  .identity-page {
    min-height: auto;
    padding-block: var(--pj-space-7);
  }
}
</style>
