<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import {
  type ProductReview,
  type ReviewReportReason,
} from "@plain-journal/foundation";
import {
  PjActionGroup,
  PjButton,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  type ReviewAccessContext,
  useProductReviewsStore,
} from "../../../entities/product-review";

const props = defineProps<{
  access: ReviewAccessContext;
  productId: string;
}>();

const route = useRoute();
const router = useRouter();
const reviews = useProductReviewsStore();
const reportingReviewId = ref<string | null>(null);
const reportReason = ref<ReviewReportReason>("OTHER");
const reportDetail = ref("");
const reportFeedback = ref<string | null>(null);

const reviewSummary = computed(() => reviews.summaries[props.productId] ?? null);
const productReviews = computed(() => reviews.productReviews[props.productId] ?? []);

function formatTimestamp(value: string): string {
  return new Date(value).toLocaleDateString("zh-CN");
}

function ratingLabel(rating: number): string {
  return `${"★".repeat(rating)}${"☆".repeat(Math.max(0, 5 - rating))}`;
}

async function toggleLike(review: ProductReview) {
  if (!props.access.authenticated) {
    await router.push({
      name: "login",
      query: { returnTo: route.fullPath },
    });
    return;
  }
  await reviews.toggleLike(props.access, review);
}

function beginReport(reviewId: string) {
  reportingReviewId.value = reviewId;
  reportReason.value = "OTHER";
  reportDetail.value = "";
  reportFeedback.value = null;
}

async function submitReport(reviewId: string) {
  const saved = await reviews.report(
    props.access,
    reviewId,
    reportReason.value,
    reportDetail.value,
  );
  if (saved) {
    reportingReviewId.value = null;
    reportFeedback.value = "举报已保存，平台会依据评价与订单事实进行审核。";
  } else if (reviews.participationUnknownReviewId === reviewId) {
    reportFeedback.value = "举报结果尚未确认。页面不会自动重提，请等待平台事实或稍后重新查看。";
  }
}

watch(
  () => [props.productId, props.access.ownerId, props.access.accessToken] as const,
  () => {
    reportingReviewId.value = null;
    reportFeedback.value = null;
    void reviews.loadProduct(props.access, props.productId);
  },
  { immediate: true },
);
</script>

<template>
  <section class="product-reviews" aria-labelledby="product-reviews-title">
    <header class="product-reviews__header">
      <div>
        <h2 id="product-reviews-title">评价</h2>
        <p>来自已完成订单的购买反馈。</p>
      </div>
      <div v-if="reviewSummary" class="review-summary" aria-label="商品评分汇总">
        <strong>{{ Number(reviewSummary.averageRating).toFixed(1) }}</strong>
        <span>/ 5 · {{ reviewSummary.reviewCount }} 条</span>
      </div>
    </header>

    <PjStatusNotice
      v-if="reviews.loadingProductId === productId"
      tone="processing"
      title="正在读取评价"
    >
      <p>页面正在查询已经发布的购买反馈。</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-else-if="reviews.productError"
      tone="danger"
      title="评价读取未完成"
      assertive
    >
      <p>{{ reviews.productError }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-else-if="productReviews.length === 0"
      tone="neutral"
      title="暂时还没有评价"
    >
      <p>只有订单完成后才能提交，平台不会生成虚构反馈。</p>
    </PjStatusNotice>
    <div v-else class="review-list">
      <article v-for="review in productReviews" :key="review.id" class="review-entry">
        <header>
          <div>
            <strong class="review-rating" :aria-label="`${review.rating} 星`">
              {{ ratingLabel(review.rating) }}
            </strong>
            <span class="review-author">{{ review.authorLabel }}</span>
          </div>
          <time :datetime="review.createdAt">
            {{ formatTimestamp(review.createdAt) }}
          </time>
        </header>
        <p class="review-content">{{ review.content }}</p>
        <small class="review-sku">{{ review.skuName }}</small>
        <blockquote v-if="review.reply">
          <strong>素简记回复</strong>
          <p>{{ review.reply.content }}</p>
        </blockquote>
        <footer>
          <PjButton
            variant="text"
            :disabled="reviews.actionReviewId === review.id"
            @click="toggleLike(review)"
          >
            {{ review.likedByViewer ? "取消有用" : "有用" }} · {{ review.likeCount }}
          </PjButton>
          <PjButton
            v-if="access.authenticated"
            variant="text"
            :disabled="reviews.actionReviewId === review.id"
            @click="beginReport(review.id)"
          >
            举报
          </PjButton>
          <RouterLink
            v-else
            class="text-action"
            :to="{ name: 'login', query: { returnTo: route.fullPath } }"
          >
            登录后参与
          </RouterLink>
        </footer>
        <form
          v-if="reportingReviewId === review.id"
          class="review-report-form"
          @submit.prevent="submitReport(review.id)"
        >
          <label>
            举报原因
            <select v-model="reportReason">
              <option value="SPAM">广告或刷屏</option>
              <option value="ABUSE">攻击或不当内容</option>
              <option value="FALSE_INFORMATION">疑似虚假信息</option>
              <option value="OTHER">其他</option>
            </select>
          </label>
          <label>
            补充说明
            <textarea
              v-model.trim="reportDetail"
              maxlength="500"
              rows="3"
              placeholder="只描述需要平台核对的事实"
            ></textarea>
          </label>
          <PjActionGroup>
            <PjButton
              type="submit"
              variant="secondary"
              :loading="reviews.actionReviewId === review.id"
              :disabled="reviews.actionReviewId === review.id"
            >
              保存举报
            </PjButton>
            <PjButton
              variant="text"
              @click="reportingReviewId = null"
            >
              取消
            </PjButton>
          </PjActionGroup>
        </form>
      </article>
    </div>
    <PjStatusNotice v-if="reportFeedback" tone="neutral">
      <p>{{ reportFeedback }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-if="reviews.participationError"
      tone="danger"
      assertive
    >
      <p>{{ reviews.participationError }}</p>
    </PjStatusNotice>
  </section>
</template>

<style scoped>
.product-reviews {
  display: grid;
  gap: var(--pj-space-6);
  padding-block: var(--pj-space-7);
  border-top: 1px solid var(--pj-color-line);
}

.product-reviews__header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: var(--pj-space-5);
}

.product-reviews__header h2,
.product-reviews__header p {
  margin: 0;
}

.product-reviews__header h2 {
  font-size: clamp(var(--pj-font-size-xl), 3vw, var(--pj-font-size-2xl));
}

.product-reviews__header p,
.review-summary span,
.review-entry time,
.review-sku {
  color: var(--pj-color-muted);
}

.product-reviews__header p {
  margin-top: var(--pj-space-2);
}

.review-summary {
  display: flex;
  align-items: baseline;
  gap: var(--pj-space-2);
  white-space: nowrap;
}

.review-summary strong {
  color: var(--pj-color-accent-strong);
  font-size: var(--pj-font-size-2xl);
  font-variant-numeric: tabular-nums;
}

.review-list {
  border-top: 1px solid var(--pj-color-line);
}

.review-entry {
  display: grid;
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-6);
  border-bottom: 1px solid var(--pj-color-line);
}

.review-entry > header,
.review-entry > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.review-entry > header > div,
.review-entry > footer {
  flex-wrap: wrap;
}

.review-entry > header > div {
  display: flex;
  align-items: baseline;
  gap: var(--pj-space-3);
}

.review-rating {
  color: var(--pj-color-accent-strong);
  letter-spacing: 0.08em;
}

.review-author,
.review-entry time,
.review-sku {
  font-size: var(--pj-font-size-sm);
}

.review-content,
.review-entry blockquote p {
  margin: 0;
  line-height: var(--pj-line-height-relaxed);
}

.review-entry blockquote {
  display: grid;
  gap: var(--pj-space-2);
  margin: 0;
  padding: var(--pj-space-4);
  border-left: 2px solid var(--pj-color-accent);
  background: var(--pj-color-surface-soft);
}

.review-report-form {
  display: grid;
  gap: var(--pj-space-4);
  padding: var(--pj-space-5);
  background: var(--pj-color-surface-soft);
}

.review-report-form label {
  display: grid;
  gap: var(--pj-space-2);
  color: var(--pj-color-muted);
  font-size: var(--pj-font-size-sm);
}

.review-report-form select,
.review-report-form textarea {
  width: 100%;
  border: 1px solid var(--pj-color-line-strong);
  border-radius: var(--pj-radius-control);
  background: var(--pj-color-surface);
  color: var(--pj-color-text);
  font: inherit;
}

.review-report-form select {
  min-height: var(--pj-control-height-md);
  padding-inline: var(--pj-space-3);
}

.review-report-form textarea {
  min-height: 6rem;
  padding: var(--pj-space-3);
  resize: vertical;
}

.review-report-form select:focus-visible,
.review-report-form textarea:focus-visible {
  outline: var(--pj-focus-ring);
  outline-offset: var(--pj-focus-offset);
}

@media (max-width: 36rem) {
  .product-reviews__header,
  .review-entry > header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
