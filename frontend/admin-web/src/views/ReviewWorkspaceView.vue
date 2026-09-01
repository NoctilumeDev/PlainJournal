<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";

import type { ReviewReport } from "@plain-journal/foundation";
import { PjActionGroup, PjButton, PjField, PjStatusNotice } from "@plain-journal/ui";

import {
  useAdminReviewStore,
  type ReviewGovernanceCommandPhase,
} from "../entities/admin-review";
import { SplitWorkbench } from "../shared/ui";
import { useStaffSessionStore } from "../stores/session";

const session = useStaffSessionStore();
const review = useAdminReviewStore();
const selectedReportId = ref<string | null>(null);
const roles = computed(() => session.profile?.roles ?? []);
const accessContext = computed(() => ({
  authorized: Boolean(
    session.authenticated
    && roles.value.some((role) => ["ADMIN", "OPERATOR"].includes(role)),
  ),
  operatorId: session.profile?.id ?? null,
  accessToken: session.accessToken,
}));

const statusOptions = [
  { value: "OPEN" as const, label: "等待审核" },
  { value: "RESOLVED" as const, label: "已完成审核" },
];

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

const selectedReport = computed(() => {
  if (review.reports.length === 0) {
    return null;
  }
  return review.reports.find((item) => item.id === selectedReportId.value)
    ?? review.reports[0];
});

const currentStatusLabel = computed(() =>
  statusOptions.find((option) => option.value === review.status)?.label
  ?? "评价举报");

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
  }[phase] as "neutral" | "processing" | "unknown" | "success" | "danger";
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

async function loadReports() {
  await review.loadReports(accessContext.value);
}

async function selectStatus(value: "OPEN" | "RESOLVED") {
  if (review.status === value && review.reports.length > 0) {
    return;
  }
  review.status = value;
  selectedReportId.value = null;
  await loadReports();
}

function selectReport(report: ReviewReport) {
  selectedReportId.value = report.id;
}

watch(accessContext, (context) => review.synchronizeAccess(context));
watch(
  () => review.reports.map((item) => item.id),
  (reportIds) => {
    if (reportIds.length > 0 && !reportIds.includes(selectedReportId.value ?? "")) {
      selectedReportId.value = reportIds[0] ?? null;
    }
  },
  { immediate: true },
);

onMounted(() => {
  review.synchronizeAccess(accessContext.value);
  void loadReports();
});
</script>

<template>
  <SplitWorkbench label="评价治理工作台" class="review-workbench">
    <template #rail>
      <div class="review-rail">
        <header class="review-rail__header">
          <p class="eyebrow">任务队列</p>
          <h1>评价治理</h1>
          <p>先按审核状态收窄，再判断一条举报事实。</p>
        </header>

        <nav class="review-status-nav" aria-label="评价举报状态筛选">
          <button
            v-for="option in statusOptions"
            :key="option.value"
            type="button"
            :aria-current="review.status === option.value ? 'page' : undefined"
            :disabled="review.loading"
            @click="selectStatus(option.value)"
          >
            <span>{{ option.label }}</span>
            <small>{{ review.status === option.value ? review.total : "—" }}</small>
          </button>
        </nav>

        <section class="review-rail__boundary" aria-labelledby="review-command-boundary">
          <p class="eyebrow">治理边界</p>
          <h2 id="review-command-boundary">Catalog 记录最终事实</h2>
          <p>
            回复与审核使用各自稳定命令 ID。结果未知时只能原样重试，不能靠列表状态猜测成功。
          </p>
        </section>

        <PjButton type="button" variant="text" :loading="review.loading" @click="loadReports">
          重新读取权威事实
        </PjButton>
      </div>
    </template>

    <template #queue>
      <div class="review-queue">
        <header class="review-panel-header">
          <div>
            <p class="eyebrow">{{ currentStatusLabel }}</p>
            <h2>{{ review.total }} 条当前举报</h2>
          </div>
          <small v-if="review.refreshedAt">读取于 {{ formatTimestamp(review.refreshedAt) }}</small>
        </header>

        <PjStatusNotice
          v-if="review.loadError"
          tone="danger"
          title="评价举报读取未完成"
          assertive
        >
          <p>{{ review.loadError }}</p>
          <p v-if="review.reports.length > 0">保留上一次已显示的 Catalog 举报事实。</p>
        </PjStatusNotice>

        <div v-if="review.loading && review.reports.length === 0" class="review-empty" role="status">
          <strong>正在读取评价举报</strong>
          <span>当前筛选条件会保留。</span>
        </div>
        <div v-else-if="!review.loading && review.reports.length === 0" class="review-empty">
          <strong>当前筛选下没有评价举报</strong>
          <span>可切换审核状态或重新读取权威事实。</span>
        </div>
        <ol v-else class="review-queue__list">
          <li v-for="report in review.reports" :key="report.id">
            <button
              type="button"
              :class="{ 'is-selected': selectedReport?.id === report.id }"
              :aria-pressed="selectedReport?.id === report.id"
              @click="selectReport(report)"
            >
              <span class="review-queue__identity">
                <code>{{ report.id }}</code>
                <strong>{{ reasonFor(report).label }}</strong>
              </span>
              <span class="review-score">{{ report.rating }} / 5</span>
              <span class="review-queue__excerpt">{{ report.reviewContent }}</span>
              <span class="review-queue__meta">
                <span class="review-status" :data-tone="report.status === 'OPEN' ? 'warning' : 'neutral'">
                  {{ report.status === "OPEN" ? "等待审核" : "审核完成" }}
                </span>
                <time :datetime="report.createdAt">{{ formatTimestamp(report.createdAt) }}</time>
              </span>
            </button>
          </li>
        </ol>
      </div>
    </template>

    <template #detail>
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
          命令：{{ review.pendingCommandLabel }}；ID：<code>{{ review.pendingCommandId }}</code>。
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

      <article v-if="selectedReport" class="review-detail">
        <header class="review-detail__header">
          <div>
            <p class="eyebrow">举报编号 {{ selectedReport.id }}</p>
            <h2>{{ reasonFor(selectedReport).label }}</h2>
          </div>
          <span class="review-status" :data-tone="selectedReport.status === 'OPEN' ? 'warning' : 'neutral'">
            {{ selectedReport.status === "OPEN" ? "等待审核" : "审核完成" }}
          </span>
        </header>

        <section class="review-detail__section" aria-labelledby="review-report-facts">
          <header><h3 id="review-report-facts">举报与评价事实</h3><p>Catalog</p></header>
          <dl class="review-facts">
            <div><dt>评价 ID</dt><dd><code>{{ selectedReport.reviewId }}</code></dd></div>
            <div><dt>商品 ID</dt><dd><code>{{ selectedReport.productId }}</code></dd></div>
            <div><dt>顾客评分</dt><dd>{{ selectedReport.rating }} / 5</dd></div>
            <div><dt>举报时间</dt><dd>{{ formatTimestamp(selectedReport.createdAt) }}</dd></div>
            <div><dt>举报状态</dt><dd>{{ selectedReport.status }}</dd></div>
            <div><dt>审核结论</dt><dd>{{ selectedReport.resolution || "尚未审核" }}</dd></div>
            <div><dt>完成时间</dt><dd>{{ formatTimestamp(selectedReport.resolvedAt) }}</dd></div>
          </dl>
        </section>

        <section class="review-detail__section review-evidence" aria-labelledby="review-public-evidence">
          <header><h3 id="review-public-evidence">公开内容与举报证据</h3></header>
          <div class="review-evidence__grid">
            <div><span>顾客公开评价</span><blockquote>{{ selectedReport.reviewContent }}</blockquote></div>
            <div><span>举报补充说明</span><p>{{ selectedReport.detail || "举报人未补充说明。" }}</p></div>
          </div>
        </section>

        <section class="review-detail__section review-next-step" aria-labelledby="review-next-step">
          <header><h3 id="review-next-step">当前判断边界</h3></header>
          <div class="review-next-step__grid">
            <div><span>核对重点</span><strong>{{ reasonFor(selectedReport).label }}</strong></div>
            <div><span>下一步</span><strong>{{ reasonFor(selectedReport).next }}</strong></div>
          </div>
        </section>

        <template v-if="selectedReport.status === 'OPEN'">
          <form class="review-action" @submit.prevent="review.reply(selectedReport, accessContext)">
            <div class="review-action__copy">
              <p class="eyebrow">一次性平台回复</p>
              <h3>补充可核实事实</h3>
              <p>回复不会改变举报状态，也不能替代审核结论。</p>
            </div>
            <PjStatusNotice
              v-if="review.confirmedReplies[selectedReport.reviewId]"
              tone="success"
              title="平台回复已确认"
            >
              <p>{{ review.confirmedReplies[selectedReport.reviewId]?.content }}</p>
            </PjStatusNotice>
            <template v-else>
              <PjField
                label="平台公开回复"
                :for-id="fieldId('review-reply', selectedReport.id)"
                hint="1–1000 字；结果未知时正文和命令 ID 会被冻结。"
                required
              >
                <textarea
                  :id="fieldId('review-reply', selectedReport.id)"
                  v-model="review.reviewForm(selectedReport.id).replyContent"
                  class="pj-control"
                  maxlength="1000"
                  rows="3"
                  required
                  :readonly="review.commandBlocked"
                  placeholder="仅记录商品、订单、履约或服务中可核实的事实"
                ></textarea>
              </PjField>
              <div class="review-command-identity">
                <span>回复命令 ID</span>
                <code>{{ review.reviewForm(selectedReport.id).replyCommandId }}</code>
              </div>
              <PjActionGroup align="end">
                <PjButton
                  type="submit"
                  variant="secondary"
                  :loading="review.submitting && review.pendingCommand?.kind === 'reply'"
                  :disabled="review.commandBlocked"
                >保存平台回复</PjButton>
              </PjActionGroup>
            </template>
          </form>

          <form class="review-action" @submit.prevent="review.moderate(selectedReport, accessContext)">
            <div class="review-action__copy">
              <p class="eyebrow">Catalog 审核审计</p>
              <h3>记录举报结论</h3>
              <p>举报成立会隐藏公开评价；驳回不会重新发布已隐藏评价。</p>
            </div>
            <div class="review-action__fields">
              <PjField label="审核结论" :for-id="fieldId('review-resolution', selectedReport.id)" required>
                <select
                  :id="fieldId('review-resolution', selectedReport.id)"
                  v-model="review.reviewForm(selectedReport.id).resolution"
                  class="pj-control"
                  :disabled="review.commandBlocked"
                >
                  <option value="REJECTED">举报不成立</option>
                  <option value="UPHELD">举报成立并隐藏评价</option>
                </select>
              </PjField>
              <PjField
                label="审核说明"
                :for-id="fieldId('review-resolution-reason', selectedReport.id)"
                hint="8–500 字；说明核对了哪些事实以及结论依据。"
                required
              >
                <textarea
                  :id="fieldId('review-resolution-reason', selectedReport.id)"
                  v-model="review.reviewForm(selectedReport.id).resolutionReason"
                  class="pj-control"
                  minlength="8"
                  maxlength="500"
                  rows="3"
                  required
                  :readonly="review.commandBlocked"
                ></textarea>
              </PjField>
            </div>
            <div class="review-command-identity">
              <span>审核命令 ID</span>
              <code>{{ review.reviewForm(selectedReport.id).moderationCommandId }}</code>
            </div>
            <PjActionGroup align="end">
              <PjButton
                type="submit"
                :loading="review.submitting && review.pendingCommand?.kind === 'moderation'"
                :disabled="review.commandBlocked"
              >提交审核结论</PjButton>
            </PjActionGroup>
          </form>
        </template>
      </article>
      <div v-else class="review-detail review-empty">
        <strong>尚未选择评价举报</strong>
        <span>先从任务队列读取并选择一条 Catalog 事实。</span>
      </div>
    </template>
  </SplitWorkbench>
</template>

<style scoped>
.review-workbench { color: var(--pj-color-text); }
.review-rail, .review-queue, .review-detail {
  min-width: 0;
  height: calc(100vh - var(--admin-shell-header-height));
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.review-rail, .review-queue { padding: var(--pj-space-5); }
.review-rail__header, .review-panel-header, .review-detail__header,
.review-detail__section > header, .review-action__copy { min-width: 0; }
.review-rail__header h1, .review-panel-header h2, .review-detail__header h2,
.review-detail__section h3, .review-action h3 {
  margin: 0;
  font-weight: 560;
  letter-spacing: 0.012em;
}
.review-rail__header h1 { font-size: clamp(1.5rem, 2.2vw, 2rem); white-space: nowrap; }
.review-rail__header > p:last-child, .review-panel-header small,
.review-detail__section > header p, .review-action__copy p:last-child,
.review-empty span { color: var(--pj-color-muted); }
.review-status-nav { display: grid; gap: var(--pj-space-1); margin-top: var(--pj-space-6); }
.review-status-nav button {
  min-height: 2.75rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-3);
  padding: var(--pj-space-2) var(--pj-space-3);
  border: 0;
  background: transparent;
  color: var(--pj-color-muted);
  cursor: pointer;
  text-align: left;
}
.review-status-nav button:hover, .review-status-nav button[aria-current="page"] {
  background: var(--pj-color-surface-soft);
  color: var(--pj-color-text);
}
.review-status-nav button[aria-current="page"] { font-weight: 650; }
.review-rail__boundary {
  margin-block: var(--pj-space-7) var(--pj-space-5);
  padding-top: var(--pj-space-5);
  border-top: 1px solid var(--pj-color-line);
}
.review-rail__boundary h2 { margin: 0; font-size: var(--pj-font-size-md); }
.review-rail__boundary p:last-child { color: var(--pj-color-muted); font-size: var(--pj-font-size-sm); }
.review-panel-header, .review-detail__header, .review-detail__section > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}
.review-panel-header { padding-bottom: var(--pj-space-5); border-bottom: 1px solid var(--pj-color-line); }
.review-panel-header h2 { font-size: var(--pj-font-size-lg); }
.review-queue > .pj-status-notice { margin-block: var(--pj-space-4); }
.review-queue__list { margin: 0; padding: 0; list-style: none; }
.review-queue__list li { border-bottom: 1px solid var(--pj-color-line); }
.review-queue__list button {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--pj-space-2) var(--pj-space-4);
  padding: var(--pj-space-4) var(--pj-space-3);
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
}
.review-queue__list button:hover, .review-queue__list button.is-selected {
  background: color-mix(in srgb, var(--pj-color-accent-soft) 28%, var(--pj-color-surface));
}
.review-queue__list button.is-selected { box-shadow: inset 0.2rem 0 0 var(--pj-color-accent-strong); }
.review-queue__identity, .review-queue__meta {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--pj-space-2);
}
.review-queue__identity { flex-wrap: wrap; }
.review-queue__identity code, .review-queue__identity strong, .review-queue__excerpt { overflow-wrap: anywhere; }
.review-score { font-weight: 650; }
.review-queue__excerpt, .review-queue__meta { color: var(--pj-color-muted); font-size: var(--pj-font-size-sm); }
.review-queue__meta { justify-content: space-between; }
.review-queue__meta time { text-align: right; }
.review-status {
  width: fit-content;
  flex: 0 0 auto;
  padding: var(--pj-space-1) var(--pj-space-2);
  border: 1px solid var(--pj-color-line);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-xs);
  line-height: 1.2;
}
.review-status[data-tone="warning"] { border-color: var(--pj-status-warning-line); color: var(--pj-status-warning-text); }
.review-detail { padding: var(--pj-space-5) var(--pj-space-6) var(--pj-space-7); }
.review-detail__header { padding-bottom: var(--pj-space-5); border-bottom: 1px solid var(--pj-color-line); }
.review-detail__header h2 { overflow-wrap: anywhere; font-size: clamp(1.5rem, 2.4vw, 2.25rem); }
.review-command-notice { margin-top: var(--pj-space-5); }
.review-detail__section, .review-action { padding-block: var(--pj-space-6); border-bottom: 1px solid var(--pj-color-line); }
.review-detail__section h3, .review-action h3 { font-size: var(--pj-font-size-md); }
.review-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--pj-space-4) var(--pj-space-7);
  margin: var(--pj-space-5) 0 0;
}
.review-facts div { min-width: 0; }
.review-facts dt, .review-evidence__grid span, .review-next-step__grid span,
.review-command-identity span { color: var(--pj-color-muted); font-size: var(--pj-font-size-xs); }
.review-facts dd { margin: var(--pj-space-1) 0 0; overflow-wrap: anywhere; }
.review-evidence__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(12rem, 0.8fr);
  gap: var(--pj-space-6);
  margin-top: var(--pj-space-5);
}
.review-evidence__grid > div { min-width: 0; display: grid; align-content: start; gap: var(--pj-space-3); }
.review-evidence blockquote, .review-evidence p {
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
.review-evidence p { color: var(--pj-color-muted); }
.review-next-step__grid {
  display: grid;
  grid-template-columns: minmax(10rem, 0.7fr) minmax(0, 1.3fr);
  margin-top: var(--pj-space-5);
  border: 1px solid var(--pj-color-line-strong);
}
.review-next-step__grid > div {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: var(--pj-space-2);
  padding: var(--pj-space-4);
}
.review-next-step__grid > div + div { border-left: 1px solid var(--pj-color-line); }
.review-next-step__grid strong { overflow-wrap: anywhere; font-size: var(--pj-font-size-sm); font-weight: 560; }
.review-action {
  display: grid;
  grid-template-columns: minmax(13rem, 0.72fr) minmax(18rem, 1.28fr);
  gap: var(--pj-space-5);
}
.review-action__copy { grid-row: 1 / span 3; }
.review-action textarea { resize: vertical; line-height: var(--pj-line-height-relaxed); }
.review-action__fields {
  display: grid;
  grid-template-columns: minmax(11rem, 0.42fr) minmax(0, 1fr);
  gap: var(--pj-space-5);
}
.review-command-identity { min-width: 0; display: grid; gap: var(--pj-space-1); }
.review-command-identity code { overflow-wrap: anywhere; }
.review-action > .pj-action-group, .review-action > .pj-status-notice { grid-column: 2; }
.review-empty { min-height: 14rem; display: grid; place-content: center; gap: var(--pj-space-2); text-align: center; }
@media (max-width: 72rem) {
  .review-detail { height: auto; overflow: visible; }
}
@media (max-width: 48rem) {
  .review-rail, .review-queue, .review-detail {
    height: auto;
    overflow: visible;
    padding: var(--pj-space-5) var(--pj-layout-gutter);
  }
  .review-facts, .review-evidence__grid, .review-next-step__grid,
  .review-action, .review-action__fields { grid-template-columns: minmax(0, 1fr); }
  .review-next-step__grid > div + div { border-top: 1px solid var(--pj-color-line); border-left: 0; }
  .review-action__copy { grid-row: auto; }
  .review-action > .pj-action-group, .review-action > .pj-status-notice { grid-column: auto; }
}
</style>
