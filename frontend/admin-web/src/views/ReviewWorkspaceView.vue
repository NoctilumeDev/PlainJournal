<script setup lang="ts">
import { computed, onMounted, watch } from "vue";

import type { ReviewReport } from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjField,
  PjPageContainer,
  PjStatusNotice,
  PjSurface,
} from "@plain-journal/ui";

import {
  useAdminReviewStore,
  type ReviewGovernanceCommandPhase,
} from "../entities/admin-review";
import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
const review = useAdminReviewStore();
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const reasonPresentation = {
  SPAM: {
    label: "垃圾或重复内容",
    next: "核对是否存在批量、重复或与商品无关的内容。",
  },
  ABUSE: {
    label: "攻击或不当表达",
    next: "只判断公开内容是否越过平台规则，不改写顾客观点。",
  },
  FALSE_INFORMATION: {
    label: "疑似错误信息",
    next: "优先核对不可变订单行、商品规格与可验证服务事实。",
  },
  OTHER: {
    label: "其他原因",
    next: "依据举报说明补充核对，不用主观偏好替代事实。",
  },
} as const;

function reasonFor(report: ReviewReport) {
  return reasonPresentation[report.reasonCode];
}

function formatTimestamp(value: string | null): string {
  return value ? new Date(value).toLocaleString("zh-CN") : "—";
}

function fieldId(prefix: string, reportId: string) {
  return `${prefix}-${reportId.replace(/[^A-Za-z0-9_-]/gu, "-")}`;
}

function noticeTone(phase: ReviewGovernanceCommandPhase) {
  return {
    idle: "neutral",
    processing: "processing",
    unknown: "unknown",
    accepted: "success",
    rejected: "danger",
  }[phase] as
    | "neutral"
    | "processing"
    | "unknown"
    | "success"
    | "danger";
}

function noticeTitle(phase: ReviewGovernanceCommandPhase) {
  return {
    idle: "评价治理命令",
    processing: "命令正在处理中",
    unknown: "命令结果未知",
    accepted: "命令结果已确认",
    rejected: "命令未被接受",
  }[phase];
}

function loadReports() {
  return review.loadReports(accessContext.value);
}

watch(accessContext, (context) => {
  review.synchronizeAccess(context);
});

onMounted(() => {
  review.synchronizeAccess(accessContext.value);
  void loadReports();
});
</script>

<template>
  <PjPageContainer as="section" size="wide" class="review-page">
    <header class="review-hero">
      <div>
        <p class="eyebrow">Catalog 公开评价治理</p>
        <h1>评价治理</h1>
        <p>
          运营人员可以给公开评价追加一次平台回复，并对顾客举报作出审核结论。
          页面只呈现 Catalog 已确认的回复、审核审计和评价可见性事实。
        </p>
      </div>
      <span class="status-label">ADMIN / OPERATOR</span>
    </header>

    <PjStatusNotice tone="neutral" title="回复与审核的恢复边界">
      <p>
        两类写入都有稳定命令 ID。网络、超时、非法响应或 5xx 后，页面冻结原命令和
        完整载荷，只允许原样重放。举报列表不返回命令 ID 或审核原因，公开评价也不能
        证明是哪条命令生效，因此它们不能替代 Catalog 幂等重放。
      </p>
    </PjStatusNotice>

    <PjSurface tone="soft" padding="large">
      <div class="review-filter">
        <PjField label="举报状态" for-id="review-report-status">
          <select
            id="review-report-status"
            v-model="review.status"
            class="pj-control"
            :disabled="review.loading"
            @change="loadReports"
          >
            <option value="OPEN">等待审核</option>
            <option value="RESOLVED">已完成审核</option>
          </select>
        </PjField>
        <div class="review-filter__summary">
          <span>{{ review.total }} 条当前举报</span>
          <small v-if="review.refreshedAt">
            最近读取 {{ formatTimestamp(review.refreshedAt) }}
          </small>
        </div>
        <PjButton
          type="button"
          variant="text"
          :loading="review.loading"
          @click="loadReports"
        >
          重新读取
        </PjButton>
      </div>
    </PjSurface>

    <PjStatusNotice
      v-if="review.commandPhase !== 'idle' && review.commandMessage"
      class="review-command-notice"
      :tone="noticeTone(review.commandPhase)"
      :title="noticeTitle(review.commandPhase)"
      :assertive="review.commandPhase === 'rejected'"
    >
      <p>{{ review.commandMessage }}</p>
      <p v-if="review.pendingReportId">
        待确认举报：<code>{{ review.pendingReportId }}</code>；
        命令：{{ review.pendingCommandLabel }}；
        ID：<code>{{ review.pendingCommandId }}</code>。
      </p>
      <template #actions>
        <PjActionGroup>
          <PjButton
            v-if="review.canRetryPending"
            type="button"
            :loading="review.submitting"
            @click="review.retryPending(accessContext)"
          >
            使用原命令 ID 重试
          </PjButton>
          <PjButton
            v-if="!review.pendingCommand"
            type="button"
            variant="text"
            @click="review.resetCommandNotice"
          >
            关闭提示
          </PjButton>
        </PjActionGroup>
      </template>
    </PjStatusNotice>

    <PjStatusNotice
      v-if="review.loadError"
      tone="danger"
      title="评价举报读取未完成"
      assertive
    >
      <p>{{ review.loadError }}</p>
      <p v-if="review.reports.length > 0">
        页面保留上一次已经显示的 Catalog 举报事实，没有把读取失败伪装成空队列。
      </p>
    </PjStatusNotice>

    <div
      v-if="review.loading && review.reports.length === 0"
      class="review-state"
      role="status"
    >
      正在读取 Catalog 评价举报…
    </div>
    <div
      v-else-if="!review.loading && review.reports.length === 0"
      class="review-state"
    >
      当前筛选下没有评价举报。
    </div>
    <section
      v-else
      class="review-list"
      aria-label="评价举报事实列表"
    >
      <PjSurface
        v-for="report in review.reports"
        :key="report.id"
        as="article"
        tone="plain"
        padding="none"
        class="review-record"
      >
        <header class="review-record__header">
          <div>
            <p class="eyebrow">{{ reasonFor(report).label }}</p>
            <h2>举报 {{ report.id }}</h2>
          </div>
          <span
            class="review-status"
            :data-tone="report.status === 'OPEN' ? 'warning' : 'neutral'"
          >
            {{ report.status === "OPEN" ? "等待审核" : "审核完成" }}
          </span>
        </header>

        <div class="review-record__journey">
          <div>
            <span>核对重点</span>
            <strong>{{ reasonFor(report).label }}</strong>
          </div>
          <p>{{ reasonFor(report).next }}</p>
        </div>

        <dl class="review-facts">
          <div>
            <dt>评价 ID</dt>
            <dd><code>{{ report.reviewId }}</code></dd>
          </div>
          <div>
            <dt>商品 ID</dt>
            <dd><code>{{ report.productId }}</code></dd>
          </div>
          <div>
            <dt>顾客评分</dt>
            <dd>{{ report.rating }} / 5</dd>
          </div>
          <div>
            <dt>举报时间</dt>
            <dd>{{ formatTimestamp(report.createdAt) }}</dd>
          </div>
          <div>
            <dt>举报状态</dt>
            <dd>{{ report.status }}</dd>
          </div>
          <div>
            <dt>审核结论</dt>
            <dd>{{ report.resolution || "尚未审核" }}</dd>
          </div>
          <div>
            <dt>完成时间</dt>
            <dd>{{ formatTimestamp(report.resolvedAt) }}</dd>
          </div>
        </dl>

        <section class="review-evidence">
          <div>
            <span>顾客公开评价</span>
            <blockquote>{{ report.reviewContent }}</blockquote>
          </div>
          <div>
            <span>举报补充说明</span>
            <p>{{ report.detail || "举报人未补充说明。" }}</p>
          </div>
        </section>

        <template v-if="report.status === 'OPEN'">
          <PjSurface
            tone="soft"
            padding="medium"
            class="review-action"
          >
            <form @submit.prevent="review.reply(report, accessContext)">
              <header>
                <div>
                  <p class="eyebrow">一次性平台回复</p>
                  <h3>补充可核实事实</h3>
                </div>
                <span>回复不会改变举报状态，也不能替代审核结论。</span>
              </header>
              <PjStatusNotice
                v-if="review.confirmedReplies[report.reviewId]"
                tone="success"
                title="平台回复已确认"
              >
                <p>{{ review.confirmedReplies[report.reviewId]?.content }}</p>
              </PjStatusNotice>
              <template v-else>
                <PjField
                  label="平台公开回复"
                  :for-id="fieldId('review-reply', report.id)"
                  hint="1–1000 字；结果未知时正文和命令 ID 会被冻结。"
                  required
                >
                  <textarea
                    :id="fieldId('review-reply', report.id)"
                    v-model="review.reviewForm(report.id).replyContent"
                    class="pj-control"
                    maxlength="1000"
                    rows="4"
                    required
                    :readonly="review.commandBlocked"
                    placeholder="仅记录商品、订单、履约或服务中可核实的事实"
                  ></textarea>
                </PjField>
                <div class="review-command-identity">
                  <span>回复命令 ID</span>
                  <code>{{ review.reviewForm(report.id).replyCommandId }}</code>
                </div>
                <PjButton
                  type="submit"
                  variant="secondary"
                  :loading="
                    review.submitting
                      && review.pendingReportId === report.id
                      && review.pendingCommand?.kind === 'reply'
                  "
                  :disabled="review.commandBlocked"
                >
                  保存平台回复
                </PjButton>
              </template>
            </form>
          </PjSurface>

          <PjSurface
            tone="soft"
            padding="medium"
            class="review-action"
          >
            <form @submit.prevent="review.moderate(report, accessContext)">
              <header>
                <div>
                  <p class="eyebrow">Catalog 审核审计</p>
                  <h3>记录举报结论</h3>
                </div>
                <span>
                  UPHELD 隐藏公开评价；REJECTED 只驳回举报，不会重新发布已隐藏评价。
                </span>
              </header>
              <div class="review-action__fields">
                <PjField
                  label="审核结论"
                  :for-id="fieldId('review-resolution', report.id)"
                  required
                >
                  <select
                    :id="fieldId('review-resolution', report.id)"
                    v-model="review.reviewForm(report.id).resolution"
                    class="pj-control"
                    :disabled="review.commandBlocked"
                  >
                    <option value="REJECTED">举报不成立</option>
                    <option value="UPHELD">举报成立并隐藏评价</option>
                  </select>
                </PjField>
                <PjField
                  label="审核说明"
                  :for-id="fieldId('review-resolution-reason', report.id)"
                  hint="8–500 字；说明核对了哪些事实以及结论依据。"
                  required
                >
                  <textarea
                    :id="fieldId('review-resolution-reason', report.id)"
                    v-model="review.reviewForm(report.id).resolutionReason"
                    class="pj-control"
                    minlength="8"
                    maxlength="500"
                    rows="4"
                    required
                    :readonly="review.commandBlocked"
                  ></textarea>
                </PjField>
              </div>
              <div class="review-command-identity">
                <span>审核命令 ID</span>
                <code>{{ review.reviewForm(report.id).moderationCommandId }}</code>
              </div>
              <PjButton
                type="submit"
                :loading="
                  review.submitting
                    && review.pendingReportId === report.id
                    && review.pendingCommand?.kind === 'moderation'
                "
                :disabled="review.commandBlocked"
              >
                提交审核结论
              </PjButton>
            </form>
          </PjSurface>
        </template>
      </PjSurface>
    </section>
  </PjPageContainer>
</template>

<style scoped>
.review-page {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
  padding-block: var(--pj-space-7) var(--pj-space-8);
}

.review-hero,
.review-filter,
.review-record__header,
.review-action header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.review-hero h1,
.review-record h2,
.review-record h3 {
  margin: 0;
}

.review-hero h1 {
  font-size: var(--pj-font-size-xl);
  font-weight: 520;
  letter-spacing: -0.04em;
}

.review-hero p:last-child {
  max-width: var(--pj-layout-reading);
  margin-bottom: 0;
  color: var(--pj-color-muted);
  line-height: var(--pj-line-height-relaxed);
}

.review-filter {
  align-items: center;
}

.review-filter .pj-field {
  width: min(100%, 22rem);
}

.review-filter__summary {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
  margin-left: auto;
  color: var(--pj-color-muted);
  text-align: right;
}

.review-filter__summary span {
  color: var(--pj-color-text);
}

.review-state {
  min-height: 14rem;
  display: grid;
  place-items: center;
  color: var(--pj-color-muted);
  border-block: 1px solid var(--pj-color-line);
}

.review-list {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-7);
}

.review-record {
  min-width: 0;
  overflow: hidden;
  border-block: 1px solid var(--pj-color-line);
}

.review-record__header,
.review-record__journey,
.review-facts,
.review-evidence,
.review-action {
  padding-inline: var(--pj-space-6);
}

.review-record__header {
  padding-block: var(--pj-space-6);
}

.review-record__header h2 {
  overflow-wrap: anywhere;
  font-size: var(--pj-font-size-xl);
  font-weight: 540;
}

.review-status {
  flex: 0 0 auto;
  padding: var(--pj-space-1) var(--pj-space-3);
  border: 1px solid var(--pj-color-line);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.review-status[data-tone="warning"] {
  border-color: var(--pj-status-warning-line);
  color: var(--pj-status-warning-text);
}

.review-record__journey {
  display: grid;
  grid-template-columns: minmax(12rem, 0.35fr) minmax(0, 1fr);
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-5);
  border-block: 1px solid var(--pj-color-line);
  background: var(--pj-color-surface-soft);
}

.review-record__journey div {
  display: grid;
  gap: var(--pj-space-1);
}

.review-record__journey span,
.review-evidence span,
.review-command-identity span {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.review-record__journey p {
  margin: 0;
  color: var(--pj-color-muted);
  line-height: var(--pj-line-height-relaxed);
}

.review-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--pj-space-4) var(--pj-space-5);
  margin: 0;
  padding-block: var(--pj-space-6);
}

.review-facts div {
  min-width: 0;
  padding-top: var(--pj-space-3);
  border-top: 1px solid var(--pj-color-line);
}

.review-facts dt {
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
}

.review-facts dd {
  margin: var(--pj-space-1) 0 0;
  overflow-wrap: anywhere;
}

.review-evidence {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(16rem, 0.8fr);
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-6);
  border-top: 1px solid var(--pj-color-line);
}

.review-evidence > div {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--pj-space-3);
}

.review-evidence blockquote,
.review-evidence p {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: var(--pj-line-height-relaxed);
}

.review-evidence blockquote {
  padding-left: var(--pj-space-4);
  border-left: 2px solid var(--pj-color-accent);
  font-size: var(--pj-font-size-lg);
}

.review-evidence p {
  color: var(--pj-color-muted);
}

.review-action {
  border-top: 1px solid var(--pj-color-line);
}

.review-action form {
  display: grid;
  gap: var(--pj-space-5);
}

.review-action header {
  align-items: flex-start;
}

.review-action header > span {
  max-width: 30rem;
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
  text-align: right;
}

.review-action__fields {
  display: grid;
  grid-template-columns: minmax(12rem, 0.35fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
}

.review-action textarea {
  resize: vertical;
  line-height: var(--pj-line-height-relaxed);
}

.review-command-identity {
  min-width: 0;
  display: grid;
  gap: var(--pj-space-1);
}

.review-command-identity code {
  overflow-wrap: anywhere;
}

@media (max-width: 64rem) {
  .review-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 48rem) {
  .review-hero,
  .review-filter,
  .review-record__header,
  .review-action header {
    align-items: flex-start;
    flex-direction: column;
  }

  .review-filter .pj-field {
    width: 100%;
  }

  .review-filter__summary {
    margin-left: 0;
    text-align: left;
  }

  .review-record__journey,
  .review-evidence,
  .review-action__fields {
    grid-template-columns: minmax(0, 1fr);
  }

  .review-action header > span {
    text-align: left;
  }
}

@media (max-width: 32rem) {
  .review-record__header,
  .review-record__journey,
  .review-facts,
  .review-evidence,
  .review-action {
    padding-inline: var(--pj-space-4);
  }

  .review-facts {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
