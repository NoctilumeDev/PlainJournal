<script setup lang="ts">
import { computed, watch } from "vue";
import { RouterLink } from "vue-router";
import {
  PjButton,
  PjPageContainer,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  formatMoney,
  type Benefit,
} from "@plain-journal/foundation";

import {
  BenefitAccessChangedError,
  type BenefitAccessContext,
  useBenefitsStore,
} from "../entities/benefit";
import { useSessionStore } from "../features/customer-session";

const store = useBenefitsStore();
const session = useSessionStore();
const accessContext = computed<BenefitAccessContext>(() => ({
  authenticated: session.authenticated,
  ownerId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function typeLabel(value: string): string {
  return {
    COUPON: "优惠券",
    RED_PACKET: "红包",
    SUBSIDY: "平台补贴",
  }[value] ?? value;
}

function statusLabel(value: string): string {
  return {
    AVAILABLE: "可用",
    LOCKED: "订单已锁定",
    REDEEMED: "已使用",
  }[value] ?? value;
}

function statusPresentation(benefit: Benefit) {
  if (benefit.status === "AVAILABLE") {
    return {
      tone: "success" as const,
      description: "满足门槛与地区条件时，可在结算中选择。",
    };
  }
  if (benefit.status === "LOCKED") {
    return {
      tone: "processing" as const,
      description: benefit.lockedOrderNo
        ? `已为订单 ${benefit.lockedOrderNo} 保留，等待订单结果。`
        : "已为一笔订单保留，等待订单结果。",
    };
  }
  if (benefit.status === "REDEEMED") {
    return {
      tone: "neutral" as const,
      description: benefit.redeemedOrderNo
        ? `已由订单 ${benefit.redeemedOrderNo} 使用。`
        : "该权益已经使用，不能再次用于结算。",
    };
  }
  return {
    tone: "attention" as const,
    description: "这是服务端返回的未识别状态，请以订单与结算事实为准。",
  };
}

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleString("zh-CN");
}

function regionLabel(level: string): string {
  return {
    PROVINCE: "省级",
    CITY: "市级",
    DISTRICT: "区县",
  }[level] ?? level;
}

watch(accessContext, async (context) => {
  try {
    await store.load(context);
  } catch (cause) {
    if (!(cause instanceof BenefitAccessChangedError)) {
      // The benefit entity keeps the current owner's read error.
    }
  }
}, { immediate: true });
</script>

<template>
  <PjPageContainer as="section" size="wide" class="benefit-center-page">
    <nav class="benefit-path" aria-label="当前位置">
      <RouterLink to="/account">账户</RouterLink>
      <span aria-hidden="true">/</span>
      <span>优惠权益</span>
    </nav>

    <header class="benefit-intro">
      <div>
        <p class="benefit-context">账户权益</p>
        <h1>优惠权益</h1>
      </div>
      <p>{{ store.availableCount }} 份当前可用</p>
    </header>

    <PjStatusNotice
      v-if="store.error"
      class="benefit-error"
      tone="danger"
      title="权益事实未能读取"
      assertive
    >
      <p>{{ store.error }}</p>
      <template #actions>
        <PjButton variant="text" @click="store.load(accessContext)">重新查询</PjButton>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="store.loading && store.benefits.length === 0"
      tone="processing"
      title="正在读取当前账户的权益"
    >
      <p>只展示当前登录账户由服务端返回的权益事实。</p>
    </PjStatusNotice>

    <PjStatusNotice
      v-else-if="!store.error && store.benefits.length === 0"
      tone="neutral"
      title="当前账户没有可展示的优惠权益"
    >
      <p>页面不会自行生成优惠；可继续浏览商品，等待真实权益发放。</p>
      <template #actions>
        <RouterLink to="/products">继续浏览商品 →</RouterLink>
      </template>
    </PjStatusNotice>

    <section
      v-else-if="store.benefits.length > 0"
      class="benefit-list"
      aria-label="当前账户优惠权益"
    >
      <article
        v-for="benefit in store.benefits"
        :key="benefit.benefitNo"
        class="benefit-row"
      >
        <header class="benefit-row__summary">
          <div>
            <p class="benefit-context">{{ typeLabel(benefit.benefitType) }}</p>
            <h2>{{ formatMoney(benefit.discountAmount) }}</h2>
            <p>满 {{ formatMoney(benefit.thresholdAmount) }} 可用</p>
          </div>
          <PjStatusNotice
            class="benefit-row__status"
            :tone="statusPresentation(benefit).tone"
            :title="statusLabel(benefit.status)"
          >
            <p>{{ statusPresentation(benefit).description }}</p>
          </PjStatusNotice>
        </header>
        <dl class="benefit-row__facts">
          <div>
            <dt>权益编号</dt>
            <dd>{{ benefit.benefitNo }}</dd>
          </div>
          <div>
            <dt>规则</dt>
            <dd>{{ benefit.ruleCode }}</dd>
          </div>
          <div>
            <dt>有效期</dt>
            <dd>{{ formatTimestamp(benefit.validFrom) }} 至 {{ formatTimestamp(benefit.validUntil) }}</dd>
          </div>
          <div v-if="benefit.lockedOrderNo">
            <dt>锁定订单</dt>
            <dd>{{ benefit.lockedOrderNo }}</dd>
          </div>
          <div v-if="benefit.redeemedOrderNo">
            <dt>核销订单</dt>
            <dd>{{ benefit.redeemedOrderNo }}</dd>
          </div>
        </dl>
        <p v-if="benefit.regions.length" class="benefit-row__regions">
          地区限制：{{
            benefit.regions
              .map((region) => `${regionLabel(region.level)} ${region.regionCode}`)
              .join("、")
          }}
        </p>
      </article>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.benefit-center-page {
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.benefit-path {
  display: flex;
  gap: var(--pj-space-2);
  margin-bottom: var(--pj-space-7);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.benefit-intro {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
  margin-bottom: var(--pj-space-6);
}

.benefit-intro h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
  line-height: var(--pj-line-height-tight);
}

.benefit-intro > p,
.benefit-context,
.benefit-row__summary > div > p:last-child,
.benefit-row__facts dt,
.benefit-row__regions {
  color: var(--pj-text-secondary);
}

.benefit-context {
  margin: 0 0 var(--pj-space-3);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.benefit-error {
  margin-bottom: var(--pj-space-5);
}

.benefit-list {
  display: grid;
}

.benefit-row {
  padding-block: var(--pj-space-6);
  border-top: 1px solid var(--pj-border-subtle);
}

.benefit-row:last-child {
  border-bottom: 1px solid var(--pj-border-subtle);
}

.benefit-row__summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(18rem, 0.55fr);
  gap: var(--pj-space-6);
  align-items: start;
}

.benefit-row__summary h2 {
  margin: 0;
  font-size: clamp(2rem, 4vw, 3.5rem);
  font-weight: 520;
  letter-spacing: -0.04em;
  line-height: var(--pj-line-height-tight);
}

.benefit-row__summary > div > p:last-child {
  margin: var(--pj-space-2) 0 0;
}

.benefit-row__facts {
  display: grid;
  margin: var(--pj-space-5) 0 0;
}

.benefit-row__facts > div {
  display: grid;
  grid-template-columns: minmax(8rem, 0.3fr) minmax(0, 1fr);
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.benefit-row__facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.benefit-row__regions {
  margin: var(--pj-space-4) 0 0;
}

@media (max-width: 48rem) {
  .benefit-intro {
    align-items: flex-start;
    flex-direction: column;
  }

  .benefit-row__summary {
    grid-template-columns: 1fr;
  }

  .benefit-row__facts > div {
    grid-template-columns: 1fr;
    gap: var(--pj-space-1);
  }
}
</style>
