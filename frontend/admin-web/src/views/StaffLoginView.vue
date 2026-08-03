<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { PjButton, PjField } from "@plain-journal/ui";

import { resolveStaffRedirect } from "../navigation";
import { useStaffSessionStore } from "../stores/session";

const route = useRoute();
const router = useRouter();
const session = useStaffSessionStore();
const email = ref("");
const password = ref("");

async function submit() {
  try {
    const allowed = await session.login({
      email: email.value,
      password: password.value,
    });
    await router.replace(allowed
      ? resolveStaffRedirect(route.query.redirect)
      : "/forbidden");
  } catch {
    // Store keeps the explicit backend or transport failure for the page.
  }
}
</script>

<template>
  <section class="admin-auth-page">
    <div class="admin-auth-panel">
      <p class="eyebrow">员工入口</p>
      <h1>进入运营事实工作区。</h1>
      <p>
        当前工作区接受 ADMIN、OPERATOR 或 WAREHOUSE，并按领域继续收窄权限。
        身份验证成功不等于获得所有管理权限。
      </p>

      <form class="admin-auth-form" @submit.prevent="submit">
        <PjField v-slot="{ describedBy, invalid }" label="员工邮箱" for-id="staff-email" required>
          <input
            id="staff-email"
            v-model.trim="email"
            class="pj-control"
            type="email"
            autocomplete="username"
            maxlength="190"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
            required
          />
        </PjField>
        <PjField v-slot="{ describedBy, invalid }" label="密码" for-id="staff-password" required>
          <input
            id="staff-password"
            v-model="password"
            class="pj-control"
            type="password"
            autocomplete="current-password"
            maxlength="128"
            :aria-describedby="describedBy"
            :aria-invalid="invalid"
            required
          />
        </PjField>
        <p v-if="session.error" class="admin-form-error" role="alert">{{ session.error }}</p>
        <PjButton type="submit" :loading="session.busy">
          {{ session.busy ? "正在核验…" : "登录工作区 →" }}
        </PjButton>
      </form>
    </div>
  </section>
</template>
