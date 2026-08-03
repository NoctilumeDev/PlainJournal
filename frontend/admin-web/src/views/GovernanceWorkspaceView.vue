<script setup lang="ts">
import { computed, onMounted, watch } from "vue";

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
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

const session = useStaffSessionStore();
const governance = useGovernanceStore();
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
  <PjPageContainer as="section" size="wide" class="governance-page">
    <header class="governance-hero">
      <div>
        <p class="eyebrow">所有者域治理</p>
        <h1>补偿与对账</h1>
        <p>
          页面只呈现 Payment 与四个所有者域已经确认的事实。
          响应丢失不会被当成失败或成功，恢复必须沿用原命令 ID。
        </p>
      </div>
      <span class="status-label">仅 ADMIN</span>
    </header>

    <PjStatusNotice tone="neutral" title="治理边界">
      <p>
        四域对账保持只读；补偿命令只推进合法状态机。
        MySQL 事实、追加式审计与幂等键共同裁决结果，页面不能直接改写成功状态。
      </p>
    </PjStatusNotice>

    <section class="governance-section" aria-labelledby="reconciliation-title">
      <header class="governance-section__header">
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
        <PjSurface
          v-for="domain in GOVERNANCE_DOMAINS"
          :key="domain"
          as="article"
          tone="plain"
          padding="medium"
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
              <small>
                {{ issue.occurrences }} 次 · 最近 {{ issue.lastDetectedAt }}
              </small>
            </li>
          </ul>
          <p v-else class="governance-empty">
            当前没有 {{ governance.reconciliationStatus }} 记录。
          </p>
        </PjSurface>
      </div>
    </section>

    <section class="governance-section" aria-labelledby="refund-retry-title">
      <PjSurface as="article" tone="raised" padding="large">
        <header class="governance-command-header">
          <div>
            <p class="eyebrow">Payment 授权补偿</p>
            <h2 id="refund-retry-title">退款渠道重派</h2>
          </div>
          <span>稳定命令 ID · 追加式审计</span>
        </header>

        <p class="governance-boundary">
          仅对进入 NEEDS_ATTENTION 的退款恢复派发资格。
          接口受理不等于渠道退款成功，最终退款事实仍由 Payment 与渠道回调裁决。
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
              {{
                governance.refundRetry.phase === "unknown"
                  ? "使用原 ID 安全重试"
                  : "授权重新派发"
              }}
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
          <div>
            <dt>退款状态</dt>
            <dd>{{ governance.refundRetry.result.status }}</dd>
          </div>
          <div>
            <dt>请求状态</dt>
            <dd>{{ governance.refundRetry.result.requestStatus }}</dd>
          </div>
          <div>
            <dt>尝试次数</dt>
            <dd>{{ governance.refundRetry.result.requestAttempts }}</dd>
          </div>
          <div>
            <dt>退款金额</dt>
            <dd>{{ governance.refundRetry.result.amount }}</dd>
          </div>
        </dl>

        <div
          v-if="governance.refundRetry.audits.length"
          class="governance-table-wrap"
          tabindex="0"
          aria-label="退款渠道重派审计表"
        >
          <table>
            <thead>
              <tr>
                <th>命令</th>
                <th>结果</th>
                <th>前后状态</th>
                <th>原因 / 操作人</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="audit in governance.refundRetry.audits"
                :key="audit.commandId"
              >
                <td><code>{{ audit.commandId }}</code></td>
                <td>
                  <strong>{{ audit.outcome }}</strong>
                  <small>{{ audit.errorCode || "无错误码" }}</small>
                </td>
                <td>
                  {{ audit.beforeRequestStatus }} → {{ audit.afterRequestStatus }}
                </td>
                <td>
                  {{ audit.reason }}
                  <small>{{ audit.operatorId }}</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PjSurface>
    </section>

    <section
      class="governance-section"
      aria-labelledby="exception-refund-title"
    >
      <PjSurface as="article" tone="raised" padding="large">
        <header class="governance-command-header">
          <div>
            <p class="eyebrow">Payment 异常支付恢复</p>
            <h2 id="exception-refund-title">全额原路退款</h2>
          </div>
          <span>Trade 权威状态 · Payment 退款事实</span>
        </header>

        <p class="governance-boundary">
          仅当 Payment 已成功且 Trade 权威状态仍为 PAYMENT_EXCEPTION 时受理。
          命令只创建退款，不直接伪造退款成功。
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
            <PjButton
              variant="text"
              @click="governance.resetPaymentExceptionRefund"
            >
              准备新命令
            </PjButton>
          </template>
        </PjStatusNotice>

        <form
          class="governance-command-form"
          @submit.prevent="submitPaymentExceptionRefund"
        >
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
              {{
                governance.paymentExceptionRefund.phase === "unknown"
                  ? "使用原 ID 安全重试"
                  : "授权创建退款"
              }}
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

        <dl
          v-if="governance.paymentExceptionRefund.result"
          class="governance-facts"
        >
          <div>
            <dt>退款号</dt>
            <dd>{{ governance.paymentExceptionRefund.result.refundNo }}</dd>
          </div>
          <div>
            <dt>退款状态</dt>
            <dd>{{ governance.paymentExceptionRefund.result.status }}</dd>
          </div>
          <div>
            <dt>请求状态</dt>
            <dd>{{ governance.paymentExceptionRefund.result.requestStatus }}</dd>
          </div>
          <div>
            <dt>退款金额</dt>
            <dd>{{ governance.paymentExceptionRefund.result.amount }}</dd>
          </div>
        </dl>

        <div
          v-if="governance.paymentExceptionRefund.audits.length"
          class="governance-table-wrap"
          tabindex="0"
          aria-label="异常支付退款审计表"
        >
          <table>
            <thead>
              <tr>
                <th>命令</th>
                <th>结果</th>
                <th>订单 / 退款</th>
                <th>原因 / 操作人</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="audit in governance.paymentExceptionRefund.audits"
                :key="audit.commandId"
              >
                <td><code>{{ audit.commandId }}</code></td>
                <td>
                  <strong>{{ audit.outcome }}</strong>
                  <small>{{ audit.errorCode || "无错误码" }}</small>
                </td>
                <td>
                  {{ audit.orderNo || "未绑定" }}
                  <small>{{ audit.refundNo || "未创建" }}</small>
                </td>
                <td>
                  {{ audit.reason }}
                  <small>{{ audit.operatorId }}</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </PjSurface>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.governance-page {
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.governance-hero,
.governance-section__header,
.governance-command-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.governance-hero h1 {
  margin: 0;
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
}

.governance-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-text-secondary);
}

.governance-section {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-5);
}

.governance-section > .pj-surface,
.governance-command-form > *,
.governance-facts > * {
  min-width: 0;
}

.governance-section :deep(.pj-status-notice__body),
.governance-section :deep(.pj-status-notice__content) {
  min-width: 0;
  overflow-wrap: anywhere;
}

.governance-section__header h2,
.governance-command-header h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
}

.governance-section__header > span,
.governance-command-header > span,
.governance-boundary,
.governance-empty {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.governance-toolbar {
  display: flex;
  align-items: flex-end;
  gap: var(--pj-space-5);
  padding-block: var(--pj-space-3);
  border-block: 1px solid var(--pj-border-subtle);
}

.governance-toolbar .pj-field {
  min-width: 12rem;
}

.governance-domain-grid {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4);
}

.governance-domain {
  min-width: 0;
}

.governance-domain h3 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
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
.governance-domain small {
  overflow-wrap: anywhere;
}

.governance-domain small {
  color: var(--pj-text-secondary);
}

.governance-command-header {
  margin-bottom: var(--pj-space-4);
}

.governance-boundary {
  max-width: var(--pj-layout-reading);
  margin-bottom: var(--pj-space-5);
}

.governance-command-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-5);
  margin-top: var(--pj-space-6);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-border-subtle);
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
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4);
  margin: var(--pj-space-6) 0 0;
}

.governance-facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-border-subtle);
}

.governance-facts dt {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.governance-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.governance-table-wrap {
  width: 100%;
  max-width: 100%;
  margin-top: var(--pj-space-6);
  overflow-x: auto;
  border-top: 1px solid var(--pj-border-strong);
}

.governance-table-wrap:focus-visible {
  outline: 0.15rem solid var(--pj-action-primary);
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
  .governance-domain-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .governance-hero,
  .governance-section__header,
  .governance-command-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .governance-command-form,
  .governance-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .governance-command-form__wide {
    grid-column: auto;
  }
}

@media (max-width: 32rem) {
  .governance-page {
    gap: var(--pj-space-6);
    padding-block: var(--pj-space-6);
  }

  .governance-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .governance-toolbar .pj-field {
    min-width: 0;
  }

  .governance-domain-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
