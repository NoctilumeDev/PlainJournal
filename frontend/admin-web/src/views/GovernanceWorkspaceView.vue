<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import type { CompensationPhase } from "../entities/governance";
import {
  GOVERNANCE_DOMAINS,
  useGovernanceStore,
} from "../entities/governance";
import { useStaffSessionStore } from "../stores/session";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjStatusNotice,
} from "@plain-journal/ui";

const session = useStaffSessionStore();
const governance = useGovernanceStore();
const activeCommand = ref<"refundRetry" | "paymentExceptionRefund">("refundRetry");
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && session.profile?.roles.includes("ADMIN"),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

function noticeTone(
  phase: CompensationPhase,
): "neutral" | "warning" | "unknown" | "success" | "danger" {
  switch (phase) {
    case "invalid":
      return "warning";
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

function noticeTitle(phase: CompensationPhase): string {
  switch (phase) {
    case "invalid":
      return "命令信息不完整";
    case "unknown":
      return "命令结果未知";
    case "accepted":
      return "命令已被权威事实确认";
    case "rejected":
      return "命令已被明确拒绝";
    default:
      return "治理命令";
  }
}

function loadIssues() {
  return governance.loadIssues(accessContext.value);
}

function submitRefundRetry() {
  return governance.submitRefundRetry(accessContext.value);
}

function loadRefundRetryAudits() {
  return governance.loadRefundRetryAudits(accessContext.value);
}

function submitPaymentExceptionRefund() {
  return governance.submitPaymentExceptionRefund(accessContext.value);
}

function loadPaymentExceptionAudits() {
  return governance.loadPaymentExceptionAudits(accessContext.value);
}

watch(accessContext, (context) => {
  governance.synchronizeAccess(context);
});

onMounted(() => {
  governance.synchronizeAccess(accessContext.value);
  void loadIssues();
});
</script>

<template>
  <section class="governance-workbench" aria-label="补偿与对账治理工作区">
    <aside class="governance-scope-pane">
      <header class="governance-scope-pane__header">
        <p class="eyebrow">所有者域治理</p>
        <h1>补偿与对账</h1>
        <p>
          只读取 Payment 与四个所有者域已经确认的事实；结果未知时沿用原命令 ID。
        </p>
      </header>

      <section class="governance-boundary-summary" aria-labelledby="governance-boundary-title">
        <p class="eyebrow">治理边界</p>
        <h2 id="governance-boundary-title">只读对账，受控补偿</h2>
        <p>
          MySQL 事实、追加式审计与幂等键共同裁决结果，页面不能直接改写成功状态。
        </p>
      </section>

      <dl class="governance-scope-facts">
        <div><dt>访问权限</dt><dd>仅 ADMIN</dd></div>
        <div><dt>事实范围</dt><dd>四个所有者域</dd></div>
        <div><dt>当前记录</dt><dd>{{ governance.totalIssues }} 条</dd></div>
      </dl>
    </aside>

    <section class="governance-reconciliation-pane" aria-labelledby="reconciliation-title">
      <header class="governance-pane-header">
        <div>
          <p class="eyebrow">Trade · Payment · Inventory · Fulfillment</p>
          <h2 id="reconciliation-title">所有者域只读对账</h2>
        </div>
        <span>{{ governance.totalIssues }} 条当前记录</span>
      </header>

      <div class="governance-toolbar">
        <PjField
          v-slot="{ describedBy }"
          label="对账状态"
          for-id="governance-status"
        >
          <select
            id="governance-status"
            v-model="governance.reconciliationStatus"
            class="pj-control"
            :aria-describedby="describedBy"
            @change="loadIssues"
          >
            <option value="OPEN">OPEN</option>
            <option value="RESOLVED">RESOLVED</option>
          </select>
        </PjField>
        <PjButton
          variant="text"
          :loading="governance.loadingIssues"
          @click="loadIssues"
        >
          刷新四域事实
        </PjButton>
      </div>

      <PjStatusNotice
        v-if="governance.issuesError"
        tone="danger"
        title="对账读取未完成"
        assertive
      >
        <p>{{ governance.issuesError }}</p>
      </PjStatusNotice>

      <div class="governance-domain-grid">
        <article
          v-for="domain in GOVERNANCE_DOMAINS"
          :key="domain"
          class="governance-domain"
        >
          <header>
            <p class="eyebrow">{{ domain }}</p>
            <h3>{{ governance.issues[domain].length }} 条</h3>
          </header>
          <ul v-if="governance.issues[domain].length">
            <li
              v-for="issue in governance.issues[domain]"
              :key="`${issue.referenceNo}:${issue.issueType}`"
            >
              <strong>{{ issue.issueType }}</strong>
              <span>{{ issue.referenceNo }}</span>
              <small>{{ issue.occurrences }} 次 · 最近 {{ issue.lastDetectedAt }}</small>
            </li>
          </ul>
          <p v-else class="governance-empty">
            当前没有 {{ governance.reconciliationStatus }} 记录。
          </p>
        </article>
      </div>
    </section>

    <section class="governance-command-pane" aria-labelledby="governance-command-title">
      <header class="governance-pane-header">
        <div>
          <p class="eyebrow">Payment 授权补偿</p>
          <h2 id="governance-command-title">当前补偿命令</h2>
        </div>
        <span>稳定命令 ID · 追加式审计</span>
      </header>

      <div class="governance-command-tabs" role="tablist" aria-label="补偿命令类型">
        <button
          type="button"
          role="tab"
          :aria-selected="activeCommand === 'refundRetry'"
          :class="{ 'is-active': activeCommand === 'refundRetry' }"
          @click="activeCommand = 'refundRetry'"
        >
          退款渠道重派
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="activeCommand === 'paymentExceptionRefund'"
          :class="{ 'is-active': activeCommand === 'paymentExceptionRefund' }"
          @click="activeCommand = 'paymentExceptionRefund'"
        >
          异常支付退款
        </button>
      </div>

      <article
        v-if="activeCommand === 'refundRetry'"
        class="governance-command"
        aria-labelledby="refund-retry-title"
      >
        <header class="governance-command-header">
          <div>
            <p class="eyebrow">退款恢复派发</p>
            <h3 id="refund-retry-title">退款渠道重派</h3>
          </div>
          <span>Payment 与渠道回调裁决最终退款事实</span>
        </header>

        <p class="governance-boundary">
          仅对进入 NEEDS_ATTENTION 的退款恢复派发资格；接口受理不等于渠道退款成功。
        </p>

        <PjStatusNotice
          v-if="governance.refundRetry.phase !== 'idle'"
          :tone="noticeTone(governance.refundRetry.phase)"
          :title="noticeTitle(governance.refundRetry.phase)"
          :assertive="governance.refundRetry.phase === 'rejected'"
        >
          <p>{{ governance.refundRetry.message }}</p>
          <template
            v-if="['accepted', 'rejected'].includes(governance.refundRetry.phase)"
            #actions
          >
            <PjButton variant="text" @click="governance.resetRefundRetry">
              准备新命令
            </PjButton>
          </template>
        </PjStatusNotice>

        <form class="governance-command-form" @submit.prevent="submitRefundRetry">
          <PjField
            v-slot="{ describedBy, invalid }"
            label="退款号"
            for-id="refund-retry-no"
            hint="只能使用 Payment 已存在的退款业务号。"
            required
          >
            <input
              id="refund-retry-no"
              v-model.trim="governance.refundRetry.referenceNo"
              class="pj-control"
              required
              maxlength="64"
              pattern="[A-Za-z0-9._:\-]+"
              :readonly="governance.refundRetry.phase === 'unknown'"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="命令 ID"
            for-id="refund-retry-command-id"
            hint="结果未知时必须原样保留并复用。"
            required
          >
            <input
              id="refund-retry-command-id"
              v-model.trim="governance.refundRetry.commandId"
              class="pj-control governance-command-id"
              required
              maxlength="64"
              readonly
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy, invalid }"
            label="补偿原因"
            for-id="refund-retry-reason"
            hint="原因进入不可变审计；结果未知时不得改写。"
            required
            class="governance-command-form__wide"
          >
            <textarea
              id="refund-retry-reason"
              v-model.trim="governance.refundRetry.reason"
              class="pj-control"
              required
              maxlength="200"
              rows="3"
              :readonly="governance.refundRetry.phase === 'unknown'"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjActionGroup class="governance-command-form__wide">
            <PjButton
              type="submit"
              :loading="governance.refundRetry.submitting"
              :disabled="governance.refundRetry.loadingAudits"
            >
              {{ governance.refundRetry.phase === "unknown" ? "使用原 ID 安全重试" : "授权重新派发" }}
            </PjButton>
            <PjButton
              variant="text"
              :loading="governance.refundRetry.loadingAudits"
              :disabled="governance.refundRetry.submitting"
              @click="loadRefundRetryAudits"
            >
              读取权威审计
            </PjButton>
          </PjActionGroup>
        </form>

        <PjStatusNotice
          v-if="governance.refundRetry.auditError"
          tone="warning"
          title="审计读取未完成"
        >
          <p>{{ governance.refundRetry.auditError }}</p>
        </PjStatusNotice>

        <dl v-if="governance.refundRetry.result" class="governance-facts">
          <div><dt>退款状态</dt><dd>{{ governance.refundRetry.result.status }}</dd></div>
          <div><dt>请求状态</dt><dd>{{ governance.refundRetry.result.requestStatus }}</dd></div>
          <div><dt>尝试次数</dt><dd>{{ governance.refundRetry.result.requestAttempts }}</dd></div>
          <div><dt>退款金额</dt><dd>{{ governance.refundRetry.result.amount }}</dd></div>
        </dl>

        <div
          v-if="governance.refundRetry.audits.length"
          class="governance-table-wrap"
          tabindex="0"
          aria-label="退款渠道重派审计表"
        >
          <table>
            <thead><tr><th>命令</th><th>结果</th><th>前后状态</th><th>原因 / 操作人</th></tr></thead>
            <tbody>
              <tr v-for="audit in governance.refundRetry.audits" :key="audit.commandId">
                <td><code>{{ audit.commandId }}</code></td>
                <td><strong>{{ audit.outcome }}</strong><small>{{ audit.errorCode || "无错误码" }}</small></td>
                <td>{{ audit.beforeRequestStatus }} → {{ audit.afterRequestStatus }}</td>
                <td>{{ audit.reason }}<small>{{ audit.operatorId }}</small></td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article
        v-else
        class="governance-command"
        aria-labelledby="exception-refund-title"
      >
        <header class="governance-command-header">
          <div>
            <p class="eyebrow">Payment 异常支付恢复</p>
            <h3 id="exception-refund-title">全额原路退款</h3>
          </div>
          <span>Trade 权威状态 · Payment 退款事实</span>
        </header>

        <p class="governance-boundary">
          仅当 Payment 已成功且 Trade 仍为 PAYMENT_EXCEPTION 时受理；命令只创建退款。
        </p>

        <PjStatusNotice
          v-if="governance.paymentExceptionRefund.phase !== 'idle'"
          :tone="noticeTone(governance.paymentExceptionRefund.phase)"
          :title="noticeTitle(governance.paymentExceptionRefund.phase)"
          :assertive="governance.paymentExceptionRefund.phase === 'rejected'"
        >
          <p>{{ governance.paymentExceptionRefund.message }}</p>
          <template
            v-if="['accepted', 'rejected'].includes(governance.paymentExceptionRefund.phase)"
            #actions
          >
            <PjButton variant="text" @click="governance.resetPaymentExceptionRefund">
              准备新命令
            </PjButton>
          </template>
        </PjStatusNotice>

        <form class="governance-command-form" @submit.prevent="submitPaymentExceptionRefund">
          <PjField
            v-slot="{ describedBy, invalid }"
            label="支付号"
            for-id="payment-exception-no"
            hint="Payment 会再次核对 Trade 的 PAYMENT_EXCEPTION 权威状态。"
            required
          >
            <input
              id="payment-exception-no"
              v-model.trim="governance.paymentExceptionRefund.referenceNo"
              class="pj-control"
              required
              maxlength="64"
              pattern="[A-Za-z0-9._:\-]+"
              :readonly="governance.paymentExceptionRefund.phase === 'unknown'"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy }"
            label="命令 ID"
            for-id="payment-exception-command-id"
            hint="响应丢失后不得生成第二个命令 ID。"
            required
          >
            <input
              id="payment-exception-command-id"
              v-model.trim="governance.paymentExceptionRefund.commandId"
              class="pj-control governance-command-id"
              required
              maxlength="64"
              readonly
              :aria-describedby="describedBy"
            />
          </PjField>
          <PjField
            v-slot="{ describedBy, invalid }"
            label="授权原因"
            for-id="payment-exception-reason"
            hint="原因与命令 ID 一起进入 Payment 追加式审计。"
            required
            class="governance-command-form__wide"
          >
            <textarea
              id="payment-exception-reason"
              v-model.trim="governance.paymentExceptionRefund.reason"
              class="pj-control"
              required
              maxlength="200"
              rows="3"
              :readonly="governance.paymentExceptionRefund.phase === 'unknown'"
              :aria-describedby="describedBy"
              :aria-invalid="invalid"
            />
          </PjField>
          <PjActionGroup class="governance-command-form__wide">
            <PjButton
              type="submit"
              :loading="governance.paymentExceptionRefund.submitting"
              :disabled="governance.paymentExceptionRefund.loadingAudits"
            >
              {{ governance.paymentExceptionRefund.phase === "unknown" ? "使用原 ID 安全重试" : "授权创建退款" }}
            </PjButton>
            <PjButton
              variant="text"
              :loading="governance.paymentExceptionRefund.loadingAudits"
              :disabled="governance.paymentExceptionRefund.submitting"
              @click="loadPaymentExceptionAudits"
            >
              读取权威审计
            </PjButton>
          </PjActionGroup>
        </form>

        <PjStatusNotice
          v-if="governance.paymentExceptionRefund.auditError"
          tone="warning"
          title="审计读取未完成"
        >
          <p>{{ governance.paymentExceptionRefund.auditError }}</p>
        </PjStatusNotice>

        <dl v-if="governance.paymentExceptionRefund.result" class="governance-facts">
          <div><dt>退款号</dt><dd>{{ governance.paymentExceptionRefund.result.refundNo }}</dd></div>
          <div><dt>退款状态</dt><dd>{{ governance.paymentExceptionRefund.result.status }}</dd></div>
          <div><dt>请求状态</dt><dd>{{ governance.paymentExceptionRefund.result.requestStatus }}</dd></div>
          <div><dt>退款金额</dt><dd>{{ governance.paymentExceptionRefund.result.amount }}</dd></div>
        </dl>

        <div
          v-if="governance.paymentExceptionRefund.audits.length"
          class="governance-table-wrap"
          tabindex="0"
          aria-label="异常支付退款审计表"
        >
          <table>
            <thead><tr><th>命令</th><th>结果</th><th>订单 / 退款</th><th>原因 / 操作人</th></tr></thead>
            <tbody>
              <tr v-for="audit in governance.paymentExceptionRefund.audits" :key="audit.commandId">
                <td><code>{{ audit.commandId }}</code></td>
                <td><strong>{{ audit.outcome }}</strong><small>{{ audit.errorCode || "无错误码" }}</small></td>
                <td>{{ audit.orderNo || "未绑定" }}<small>{{ audit.refundNo || "未创建" }}</small></td>
                <td>{{ audit.reason }}<small>{{ audit.operatorId }}</small></td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>
    </section>
  </section>
</template>

<style scoped>
.governance-workbench {
  --pj-focus-ring: var(--pj-brand-primary);
  --pj-color-focus: var(--pj-focus-ring);

  min-width: 0;
  min-height: calc(100vh - var(--admin-shell-header-height));
  display: grid;
  grid-template-columns:
    clamp(15rem, 20vw, 18rem)
    minmax(25rem, 0.9fr)
    minmax(28rem, 1.1fr);
  background: var(--pj-surface-default);
}

.governance-scope-pane,
.governance-reconciliation-pane,
.governance-command-pane {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--pj-space-6);
  padding: clamp(1rem, 2vw, 1.75rem);
}

.governance-scope-pane,
.governance-reconciliation-pane {
  border-right: 1px solid var(--pj-border-subtle);
}

.governance-scope-pane__header h1,
.governance-boundary-summary h2,
.governance-pane-header h2,
.governance-command-header h3,
.governance-domain h3 {
  margin: 0;
}

.governance-scope-pane__header h1 {
  font-size: clamp(1.7rem, 2.8vw, 2.45rem);
  font-weight: 520;
  letter-spacing: 0.035em;
}

.governance-scope-pane__header > p:last-child,
.governance-boundary-summary p:last-child,
.governance-pane-header > span,
.governance-command-header > span,
.governance-boundary,
.governance-empty {
  color: var(--pj-text-secondary);
}

.governance-boundary-summary,
.governance-scope-facts {
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
}

.governance-boundary-summary {
  display: grid;
  gap: var(--pj-space-3);
}

.governance-boundary-summary h2 {
  font-size: var(--pj-font-size-md);
}

.governance-scope-facts {
  display: grid;
  gap: var(--pj-space-3);
  margin: 0;
}

.governance-scope-facts div {
  display: flex;
  justify-content: space-between;
  gap: var(--pj-space-3);
}

.governance-scope-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  white-space: nowrap;
}

.governance-scope-facts dd {
  margin: 0;
  text-align: right;
}

.governance-pane-header,
.governance-command-header,
.governance-domain > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.governance-pane-header h2,
.governance-command-header h3 {
  font-size: var(--pj-font-size-lg);
  font-weight: 560;
  letter-spacing: 0.025em;
}

.governance-pane-header > span,
.governance-command-header > span,
.governance-boundary,
.governance-empty {
  font-size: var(--pj-font-size-sm);
}

.governance-toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-3);
  border-block: 1px solid var(--pj-border-subtle);
}

.governance-toolbar .pj-field {
  min-width: 11rem;
}

.governance-domain-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-5) var(--pj-space-4);
}

.governance-domain {
  min-width: 0;
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.governance-domain h3 {
  font-size: var(--pj-font-size-md);
}

.governance-domain ul {
  display: grid;
  gap: var(--pj-space-3);
  margin: var(--pj-space-4) 0 0;
  padding: 0;
  list-style: none;
}

.governance-domain li {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.governance-domain span,
.governance-domain small,
.governance-command-id,
.governance-facts dd {
  overflow-wrap: anywhere;
}

.governance-domain small {
  color: var(--pj-text-secondary);
}

.governance-empty {
  margin-bottom: 0;
}

.governance-command-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-block: 1px solid var(--pj-border-subtle);
}

.governance-command-tabs button {
  min-height: 3rem;
  padding: var(--pj-space-3) var(--pj-space-4);
  border: 0;
  border-bottom: 0.15rem solid transparent;
  background: transparent;
  color: var(--pj-text-secondary);
  font: inherit;
  cursor: pointer;
}

.governance-command-tabs button + button {
  border-left: 1px solid var(--pj-border-subtle);
}

.governance-command-tabs button.is-active {
  border-bottom-color: var(--pj-brand-primary);
  color: var(--pj-text-primary);
  font-weight: 650;
}

.governance-command-tabs button:focus-visible {
  outline: 0.15rem solid var(--pj-focus-ring);
  outline-offset: -0.2rem;
}

.governance-command {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.governance-boundary {
  margin: 0;
  padding-block: var(--pj-space-4);
  border-block: 1px solid var(--pj-border-subtle);
}

.governance-command :deep(.pj-status-notice),
.governance-command :deep(.pj-status-notice__body),
.governance-command :deep(.pj-status-notice__content) {
  min-width: 0;
}

.governance-command :deep(.pj-status-notice) {
  align-items: stretch;
  flex-direction: column;
}

.governance-command-form {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-5);
}

.governance-command-form > *,
.governance-facts > * {
  min-width: 0;
}

.governance-command-form__wide {
  grid-column: 1 / -1;
}

.governance-command-form textarea {
  resize: vertical;
  line-height: 1.5;
}

.governance-command-form .pj-control {
  min-width: 0;
  max-width: 100%;
}

.governance-command-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: var(--pj-font-size-xs);
}

.governance-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin: 0;
  border-block: 1px solid var(--pj-border-subtle);
}

.governance-facts div {
  min-width: 0;
  padding: var(--pj-space-4);
}

.governance-facts div:nth-child(even) {
  border-left: 1px solid var(--pj-border-subtle);
}

.governance-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.governance-facts dd {
  margin: var(--pj-space-1) 0 0;
}

.governance-table-wrap {
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  border-top: 1px solid var(--pj-border-strong);
}

.governance-table-wrap:focus-visible {
  outline: 0.15rem solid var(--pj-focus-ring);
  outline-offset: var(--pj-space-1);
}

.governance-table-wrap table {
  width: 100%;
  min-width: 52rem;
  border-collapse: collapse;
  text-align: left;
}

.governance-table-wrap th,
.governance-table-wrap td {
  padding: var(--pj-space-4) var(--pj-space-3);
  border-bottom: 1px solid var(--pj-border-subtle);
  vertical-align: top;
}

.governance-table-wrap th {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
  font-weight: 650;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.governance-table-wrap td:first-child {
  max-width: 19rem;
}

.governance-table-wrap code {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xs);
}

.governance-table-wrap strong,
.governance-table-wrap small {
  display: block;
}

.governance-table-wrap small {
  margin-top: var(--pj-space-1);
  color: var(--pj-text-secondary);
}

@media (max-width: 72rem) {
  .governance-workbench {
    grid-template-columns: minmax(15rem, 18rem) minmax(0, 1fr);
  }

  .governance-reconciliation-pane {
    border-right: 0;
  }

  .governance-command-pane {
    grid-column: 1 / -1;
    border-top: 1px solid var(--pj-border-subtle);
  }
}

@media (max-width: 48rem) {
  .governance-workbench {
    min-height: auto;
    grid-template-columns: minmax(0, 1fr);
  }

  .governance-scope-pane,
  .governance-reconciliation-pane,
  .governance-command-pane {
    grid-column: auto;
    padding: var(--pj-space-5);
    border-right: 0;
  }

  .governance-reconciliation-pane,
  .governance-command-pane {
    border-top: 1px solid var(--pj-border-subtle);
  }

  .governance-pane-header,
  .governance-command-header {
    flex-direction: column;
  }

  .governance-command-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .governance-command-form__wide {
    grid-column: auto;
  }
}

@media (max-width: 32rem) {
  .governance-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .governance-toolbar .pj-field {
    min-width: 0;
  }

  .governance-domain-grid,
  .governance-command-tabs,
  .governance-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .governance-command-tabs button + button,
  .governance-facts div:nth-child(even) {
    border-left: 0;
  }

  .governance-command-tabs button + button,
  .governance-facts div + div {
    border-top: 1px solid var(--pj-border-subtle);
  }
}
</style>
