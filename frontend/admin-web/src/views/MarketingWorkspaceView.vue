<script setup lang="ts">
import { computed, watch } from "vue";

import {
  type MarketingCommandPhase,
  useAdminMarketingStore,
} from "../entities/admin-marketing";
import { useStaffSessionStore } from "../stores/session";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjStatusNotice,
} from "@plain-journal/ui";

const session = useStaffSessionStore();
const marketing = useAdminMarketingStore();
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function noticeTone(
  phase: MarketingCommandPhase,
): "neutral" | "processing" | "unknown" | "success" | "danger" {
  switch (phase) {
    case "processing":
      return "processing";
    case "unknown":
      return "unknown";
    case "accepted":
      return "success";
    case "rejected":
      return "danger";
    default:
      return "neutral";
  }
}

function noticeTitle(phase: MarketingCommandPhase): string {
  switch (phase) {
    case "processing":
      return "营销命令正在确认";
    case "unknown":
      return "营销命令结果未知";
    case "accepted":
      return "Marketing 已确认";
    case "rejected":
      return "命令已被明确拒绝";
    default:
      return "营销命令";
  }
}

watch(accessContext, (context) => {
  marketing.synchronizeAccess(context);
}, { immediate: true });
</script>

<template>
  <section class="marketing-workbench" aria-label="营销权益并列命令工作区">
    <aside class="marketing-scope-pane">
      <header class="marketing-scope-pane__header">
        <p class="eyebrow">Marketing 所有者域</p>
        <h1>营销权益</h1>
        <p>规则定义优惠事实，发放命令把一条既有规则交给指定顾客。</p>
      </header>

      <section class="marketing-boundary-summary" aria-labelledby="marketing-boundary-title">
        <p class="eyebrow">恢复边界</p>
        <h2 id="marketing-boundary-title">两类命令，两种恢复方式</h2>
        <p>规则创建没有安全重试键；权益发放由原顾客、原规则与原 grantKey 幂等裁决。</p>
      </section>

      <dl class="marketing-scope-facts">
        <div><dt>当前权限</dt><dd>ADMIN / OPERATOR</dd></div>
        <div><dt>规则创建</dt><dd>不可盲目重试</dd></div>
        <div><dt>权益发放</dt><dd>沿用原 grantKey</dd></div>
      </dl>

      <PjStatusNotice
        v-if="marketing.commandPhase !== 'idle'"
        class="marketing-command-notice"
        :tone="noticeTone(marketing.commandPhase)"
        :title="noticeTitle(marketing.commandPhase)"
        :assertive="marketing.commandPhase === 'rejected'"
      >
        <p>{{ marketing.commandMessage }}</p>
        <p v-if="marketing.pendingReferenceNo">
          待确认业务：<code>{{ marketing.pendingReferenceNo }}</code>
          · {{ marketing.pendingCommandLabel }}
        </p>
        <template #actions>
          <PjActionGroup :stack-on-compact="true">
            <PjButton
              v-if="marketing.canRetryPending"
              variant="text"
              :loading="marketing.submitting"
              @click="marketing.retryPending(accessContext)"
            >
              使用原 grantKey 重试
            </PjButton>
            <PjButton
              v-if="['accepted', 'rejected'].includes(marketing.commandPhase)"
              variant="text"
              @click="marketing.resetCommandNotice"
            >
              收起结果
            </PjButton>
          </PjActionGroup>
        </template>
      </PjStatusNotice>
    </aside>

    <section class="marketing-section marketing-rule-pane" aria-labelledby="marketing-rule-title">
      <header class="marketing-section__header">
        <div>
          <p class="eyebrow">规则事实</p>
          <h2 id="marketing-rule-title">创建优惠规则</h2>
        </div>
        <small>提交前冻结完整规则载荷</small>
      </header>

      <form class="marketing-form marketing-form--rule" @submit.prevent="marketing.createRule(accessContext)">
          <PjField
            v-slot="{ describedBy }"
            label="规则代码"
            for-id="marketing-rule-code"
            hint="规则代码全局唯一，但不能作为安全重试键。"
            required
          >
            <input
              id="marketing-rule-code"
              v-model.trim="marketing.rule.ruleCode"
              class="pj-control"
              required
              maxlength="64"
              pattern="[A-Za-z0-9_\-]+"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="规则名称"
            for-id="marketing-rule-name"
            required
          >
            <input
              id="marketing-rule-name"
              v-model.trim="marketing.rule.name"
              class="pj-control"
              required
              maxlength="120"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="权益类型"
            for-id="marketing-benefit-type"
            required
          >
            <select
              id="marketing-benefit-type"
              v-model="marketing.rule.benefitType"
              class="pj-control"
              required
              :disabled="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            >
              <option value="COUPON">优惠券</option>
              <option value="RED_PACKET">红包</option>
              <option value="SUBSIDY">补贴</option>
            </select>
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="使用门槛"
            for-id="marketing-threshold"
            required
          >
            <input
              id="marketing-threshold"
              v-model.trim="marketing.rule.thresholdAmount"
              class="pj-control"
              required
              inputmode="decimal"
              pattern="[0-9]+(\.[0-9]{1,2})?"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="优惠金额"
            for-id="marketing-discount"
            required
          >
            <input
              id="marketing-discount"
              v-model.trim="marketing.rule.discountAmount"
              class="pj-control"
              required
              inputmode="decimal"
              pattern="[0-9]+(\.[0-9]{1,2})?"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="叠加顺序"
            for-id="marketing-stack-order"
            hint="数字越小越先参与营销计算。"
            required
          >
            <input
              id="marketing-stack-order"
              v-model="marketing.rule.stackOrder"
              class="pj-control"
              required
              type="number"
              min="0"
              max="2147483647"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="开始时间"
            for-id="marketing-valid-from"
            required
          >
            <input
              id="marketing-valid-from"
              v-model="marketing.rule.validFrom"
              class="pj-control"
              required
              type="datetime-local"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="结束时间"
            for-id="marketing-valid-until"
            required
          >
            <input
              id="marketing-valid-until"
              v-model="marketing.rule.validUntil"
              class="pj-control"
              required
              type="datetime-local"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="地区层级"
            for-id="marketing-region-level"
            hint="不选择时表示不限地区。"
          >
            <select
              id="marketing-region-level"
              v-model="marketing.rule.regionLevel"
              class="pj-control"
              :disabled="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            >
              <option value="">不限地区</option>
              <option value="PROVINCE">省</option>
              <option value="CITY">市</option>
              <option value="DISTRICT">区县</option>
            </select>
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="地区代码"
            for-id="marketing-region-code"
            :required="Boolean(marketing.rule.regionLevel)"
          >
            <input
              id="marketing-region-code"
              v-model.trim="marketing.rule.regionCode"
              class="pj-control"
              inputmode="numeric"
              pattern="[0-9]{6}"
              :required="Boolean(marketing.rule.regionLevel)"
              :disabled="!marketing.rule.regionLevel"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjButton type="submit" :disabled="marketing.commandBlocked">
            提交规则创建
          </PjButton>
      </form>
      <p class="marketing-boundary">
        如果提交进入 MySQL 后响应丢失，页面保留完整载荷，但不会通过重复 POST 猜测成功。
      </p>

      <article v-if="marketing.createdRule" class="marketing-fact">
        <header>
          <div>
            <p class="eyebrow">本次权威返回</p>
            <h3>{{ marketing.createdRule.name }}</h3>
          </div>
          <span class="status-label">{{ marketing.createdRule.status }}</span>
        </header>
        <dl class="marketing-facts">
          <div><dt>规则代码</dt><dd>{{ marketing.createdRule.ruleCode }}</dd></div>
          <div><dt>权益类型</dt><dd>{{ marketing.createdRule.benefitType }}</dd></div>
          <div><dt>使用门槛</dt><dd>¥{{ marketing.createdRule.thresholdAmount }}</dd></div>
          <div><dt>优惠金额</dt><dd>¥{{ marketing.createdRule.discountAmount }}</dd></div>
          <div><dt>叠加顺序</dt><dd>{{ marketing.createdRule.stackOrder }}</dd></div>
          <div><dt>版本</dt><dd>{{ marketing.createdRule.version }}</dd></div>
        </dl>
      </article>
    </section>

    <section class="marketing-section marketing-grant-pane" aria-labelledby="marketing-grant-title">
      <header class="marketing-section__header">
        <div>
          <p class="eyebrow">幂等发放</p>
          <h2 id="marketing-grant-title">向顾客发放权益</h2>
        </div>
        <small>同顾客、同 grantKey、同规则</small>
      </header>

      <form class="marketing-form marketing-form--grant" @submit.prevent="marketing.grantBenefit(accessContext)">
          <PjField
            v-slot="{ describedBy }"
            label="顾客 ID"
            for-id="marketing-user-id"
            required
          >
            <input
              id="marketing-user-id"
              v-model.trim="marketing.grant.userId"
              class="pj-control"
              required
              inputmode="numeric"
              pattern="[0-9]+"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="规则代码"
            for-id="marketing-grant-rule-code"
            required
          >
            <input
              id="marketing-grant-rule-code"
              v-model.trim="marketing.grant.ruleCode"
              class="pj-control"
              required
              maxlength="64"
              pattern="[A-Za-z0-9_\-]+"
              :readonly="Boolean(marketing.pendingCommand)"
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="发放键"
            for-id="marketing-grant-key"
            hint="页面生成并持久化；响应未知时不得更换。"
            required
          >
            <input
              id="marketing-grant-key"
              v-model.trim="marketing.grant.grantKey"
              class="pj-control marketing-command-id"
              required
              maxlength="100"
              readonly
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjButton type="submit" :disabled="marketing.commandBlocked">
            提交权益发放
          </PjButton>
      </form>
      <p class="marketing-boundary">
        <code>userId + grantKey</code> 唯一约束裁决发放；响应丢失后只能重放原顾客、原规则和原键。
      </p>

      <article v-if="marketing.grantedBenefit" class="marketing-fact">
        <header>
          <div>
            <p class="eyebrow">本次权威返回</p>
            <h3>{{ marketing.grantedBenefit.benefitNo }}</h3>
          </div>
          <span class="status-label">{{ marketing.grantedBenefit.status }}</span>
        </header>
        <dl class="marketing-facts">
          <div><dt>顾客 ID</dt><dd>{{ marketing.grantedBenefit.userId }}</dd></div>
          <div><dt>规则代码</dt><dd>{{ marketing.grantedBenefit.ruleCode }}</dd></div>
          <div><dt>权益类型</dt><dd>{{ marketing.grantedBenefit.benefitType }}</dd></div>
          <div><dt>使用门槛</dt><dd>¥{{ marketing.grantedBenefit.thresholdAmount }}</dd></div>
          <div><dt>优惠金额</dt><dd>¥{{ marketing.grantedBenefit.discountAmount }}</dd></div>
          <div><dt>锁定订单</dt><dd>{{ marketing.grantedBenefit.lockedOrderNo ?? "无" }}</dd></div>
        </dl>
      </article>
    </section>
  </section>
</template>

<style scoped>
.marketing-workbench {
  --pj-focus-ring: var(--pj-brand-primary);
  --pj-color-focus: var(--pj-focus-ring);

  min-width: 0;
  min-height: calc(100vh - var(--admin-shell-header-height));
  display: grid;
  grid-template-columns:
    clamp(15rem, 20vw, 18rem)
    minmax(28rem, 1.15fr)
    minmax(23rem, 0.85fr);
  background: var(--pj-surface-default);
}

.marketing-scope-pane,
.marketing-section {
  min-width: 0;
  padding: clamp(1rem, 2vw, 1.75rem);
}

.marketing-scope-pane,
.marketing-section {
  display: grid;
  align-content: start;
  gap: var(--pj-space-6);
}

.marketing-scope-pane,
.marketing-rule-pane {
  border-right: 1px solid var(--pj-border-subtle);
}

.marketing-scope-pane__header h1,
.marketing-boundary-summary h2,
.marketing-section__header h2,
.marketing-fact h3 {
  margin: 0;
}

.marketing-scope-pane__header h1 {
  font-size: clamp(1.7rem, 2.8vw, 2.45rem);
  font-weight: 520;
  letter-spacing: 0.035em;
}

.marketing-scope-pane__header > p:last-child,
.marketing-boundary-summary p:last-child,
.marketing-section__header small,
.marketing-boundary {
  color: var(--pj-text-secondary);
}

.marketing-scope-pane__header > p:last-child {
  margin-bottom: 0;
}

.marketing-boundary-summary,
.marketing-scope-facts,
.marketing-boundary,
.marketing-fact {
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.marketing-boundary-summary {
  display: grid;
  gap: var(--pj-space-3);
}

.marketing-boundary-summary h2 {
  font-size: var(--pj-font-size-md);
}

.marketing-scope-facts {
  display: grid;
  gap: var(--pj-space-3);
  margin: 0;
}

.marketing-scope-facts div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.marketing-scope-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  white-space: nowrap;
}

.marketing-scope-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
  text-align: right;
}

.marketing-command-notice,
.marketing-command-notice :deep(.pj-status-notice__body),
.marketing-command-notice :deep(.pj-status-notice__content) {
  min-width: 0;
}

.marketing-command-notice {
  align-items: stretch;
  flex-direction: column;
}

.marketing-command-notice :deep(.pj-status-notice__actions) {
  width: 100%;
}

.marketing-command-notice code,
.marketing-command-id,
.marketing-facts dd {
  overflow-wrap: anywhere;
}

.marketing-section__header,
.marketing-fact > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.marketing-section__header h2 {
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
  letter-spacing: 0.025em;
}

.marketing-form {
  min-width: 0;
  display: grid;
  align-items: end;
  gap: var(--pj-space-5);
}

.marketing-form--rule {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.marketing-form--grant {
  grid-template-columns: minmax(0, 1fr);
}

.marketing-boundary {
  margin: 0;
  padding-bottom: var(--pj-space-5);
  border-bottom: 1px solid var(--pj-border-subtle);
  font-size: var(--pj-font-size-sm);
}

.marketing-command-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--pj-font-size-xs);
}

.marketing-fact {
  min-width: 0;
}

.marketing-fact > header {
  align-items: flex-start;
}

.marketing-fact h3 {
  font-size: var(--pj-font-size-lg);
}

.marketing-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: var(--pj-space-5) 0 0;
  border-block: 1px solid var(--pj-border-subtle);
}

.marketing-facts div {
  min-width: 0;
  padding: var(--pj-space-4);
}

.marketing-facts div:nth-child(even) {
  border-left: 1px solid var(--pj-border-subtle);
}

.marketing-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.marketing-facts dd {
  margin: var(--pj-space-2) 0 0;
}

@media (max-width: 72rem) {
  .marketing-workbench {
    grid-template-columns: minmax(15rem, 18rem) minmax(0, 1fr);
  }

  .marketing-rule-pane {
    border-right: 0;
  }

  .marketing-grant-pane {
    grid-column: 1 / -1;
    border-top: 1px solid var(--pj-border-subtle);
  }
}

@media (max-width: 48rem) {
  .marketing-workbench {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .marketing-scope-pane,
  .marketing-section {
    padding: var(--pj-space-5);
    border-right: 0;
  }

  .marketing-rule-pane,
  .marketing-grant-pane {
    grid-column: auto;
    border-top: 1px solid var(--pj-border-subtle);
  }

  .marketing-section__header,
  .marketing-fact > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .marketing-form--rule {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 32rem) {
  .marketing-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .marketing-facts div:nth-child(even) {
    border-left: 0;
  }

  .marketing-facts div + div {
    border-top: 1px solid var(--pj-border-subtle);
  }
}
</style>
