<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";

import type { ReviewEligibility } from "@plain-journal/foundation";
import {
  PjButton,
  PjStatusNotice,
} from "@plain-journal/ui";

import {
  type ReviewAccessContext,
  useProductReviewsStore,
} from "../../../entities/product-review";

const props = defineProps<{
  access: ReviewAccessContext;
  orderNo: string;
}>();

const reviews = useProductReviewsStore();
const feedback = ref<string | null>(null);
const reviewForms = reactive<Record<string, {
  rating: number;
  content: string;
  anonymous: boolean;
}>>({});

const orderReviewEligibilities = computed(() =>
  reviews.eligibilities[props.orderNo] ?? []);

function clearForms() {
  for (const key of Object.keys(reviewForms)) {
    delete reviewForms[key];
  }
}

function reviewForm(eligibilityId: string) {
  reviewForms[eligibilityId] ??= {
    rating: 5,
    content: "",
    anonymous: false,
  };
  return reviewForms[eligibilityId];
}

async function submitReview(eligibility: ReviewEligibility) {
  feedback.value = null;
  const form = reviewForm(eligibility.id);
  const result = await reviews.submit(
    props.access,
    props.orderNo,
    eligibility,
    form.rating,
    form.content,
    form.anonymous,
  );
  if (result) {
    form.content = "";
    feedback.value = result.recovered
      ? "已从 Catalog 评价资格恢复提交结果；不会重复累加商品评分。"
      : "Catalog 已保存评价事实，商品评分与公开列表已同步更新。";
  } else if (reviews.submissionUnknown) {
    feedback.value = "评价结果尚未确认。原幂等键和原内容已按当前账户保留。";
  }
}

watch(
  () => [props.orderNo, props.access.ownerId, props.access.accessToken] as const,
  () => {
    feedback.value = null;
    clearForms();
    void reviews.loadEligibilities(props.access, props.orderNo);
  },
  { immediate: true },
);
</script>

<template>
  <section
    class="order-journey-section order-review-entry"
    aria-labelledby="order-review-title"
  >
    <header class="review-section-header">
      <p>购买评价</p>
      <h2 id="order-review-title">完成订单后再评价</h2>
      <span>每个不可变订单行只有一次评价资格。</span>
    </header>
    <p class="order-review-entry__intro">
      提交结果未知时会先查询已保存事实；仍无法确认时保留原幂等键和原内容。
    </p>
    <p
      v-if="reviews.loadingOrderNo === orderNo"
      class="checkout-note"
      role="status"
    >
      正在等待 Catalog 读取订单完成资格…
    </p>
    <div v-else-if="orderReviewEligibilities.length" class="order-review-list">
      <article
        v-for="eligibility in orderReviewEligibilities"
        :key="eligibility.id"
        class="order-review-card"
      >
        <header>
          <div>
            <strong>{{ eligibility.productTitle }}</strong>
            <p>{{ eligibility.skuName }} · {{ eligibility.quantity }} 件</p>
          </div>
          <span>{{ eligibility.status === "REVIEWED" ? "已评价" : "可评价" }}</span>
        </header>
        <RouterLink
          v-if="eligibility.status === 'REVIEWED'"
          class="text-action"
          :to="{
            name: 'product-detail',
            params: { productId: eligibility.productId },
          }"
        >
          查看商品评价
        </RouterLink>
        <form
          v-else
          class="order-review-form"
          @submit.prevent="submitReview(eligibility)"
        >
          <label>
            评分
            <select v-model.number="reviewForm(eligibility.id).rating">
              <option :value="5">5 星 · 很满意</option>
              <option :value="4">4 星 · 满意</option>
              <option :value="3">3 星 · 一般</option>
              <option :value="2">2 星 · 不满意</option>
              <option :value="1">1 星 · 很不满意</option>
            </select>
          </label>
          <label>
            评价内容
            <textarea
              v-model.trim="reviewForm(eligibility.id).content"
              required
              maxlength="2000"
              rows="4"
              placeholder="写下实际收到商品后的体验"
            ></textarea>
          </label>
          <label class="review-anonymous-option">
            <input
              v-model="reviewForm(eligibility.id).anonymous"
              type="checkbox"
            />
            公开列表使用匿名顾客标识
          </label>
          <PjButton
            type="submit"
            :loading="reviews.submittingEligibilityId === eligibility.id"
          >
            {{
              reviews.currentPending?.eligibilityId === eligibility.id
                ? "查询并使用原内容安全重试"
                : "提交评价"
            }}
          </PjButton>
        </form>
      </article>
    </div>
    <PjStatusNotice
      v-else
      tone="processing"
      title="评价资格仍在收敛"
    >
      <p>
        订单已完成，但评价资格可能仍在通过事件同步。
        当前空列表不会被解释为永久不可评价。
      </p>
      <template #actions>
        <PjButton
          variant="secondary"
          @click="reviews.loadEligibilities(access, orderNo)"
        >
          重新查询评价资格
        </PjButton>
      </template>
    </PjStatusNotice>
    <PjStatusNotice v-if="feedback" tone="success" title="评价事实已更新">
      <p>{{ feedback }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-if="reviews.submissionError"
      :tone="reviews.submissionUnknown ? 'unknown' : 'danger'"
      :title="reviews.submissionUnknown ? '评价结果待确认' : '评价提交未完成'"
      :assertive="!reviews.submissionUnknown"
    >
      <p>{{ reviews.submissionError }}</p>
    </PjStatusNotice>
    <PjStatusNotice
      v-else-if="reviews.eligibilityError"
      tone="danger"
      title="评价资格读取未完成"
      assertive
    >
      <p>{{ reviews.eligibilityError }}</p>
    </PjStatusNotice>
  </section>
</template>

<style scoped>
.order-review-entry {
  display: grid;
  gap: var(--pj-space-5);
}

.review-section-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--pj-space-2) var(--pj-space-4);
  align-items: baseline;
  padding-bottom: var(--pj-space-4);
  border-bottom: 1px solid var(--pj-border-strong);
}

.review-section-header > p,
.review-section-header > span,
.order-review-entry__intro,
.order-review-card header p {
  margin: 0;
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
}

.review-section-header h2 {
  margin: 0;
  font-size: var(--pj-font-size-lg);
  font-weight: 600;
}

.order-review-list {
  display: grid;
  gap: var(--pj-space-5);
}

.order-review-card {
  display: grid;
  gap: var(--pj-space-4);
  padding-block: var(--pj-space-4);
  border-block: 1px solid var(--pj-border-subtle);
}

.order-review-card > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--pj-space-4);
}

.order-review-card header p {
  margin-top: var(--pj-space-1);
}

.order-review-card > header > span {
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-xs);
}

.order-review-form {
  display: grid;
  gap: var(--pj-space-4);
  padding-top: var(--pj-space-4);
  border-top: 1px solid var(--pj-border-subtle);
}

.order-review-form label {
  display: grid;
  gap: var(--pj-space-2);
  color: var(--pj-text-secondary);
  font-size: var(--pj-font-size-sm);
  font-weight: 600;
}

.order-review-form select,
.order-review-form textarea {
  width: 100%;
  padding: var(--pj-space-3);
  border: 1px solid var(--pj-border-strong);
  background: var(--pj-surface-default);
  color: var(--pj-text-primary);
}

.order-review-form textarea {
  resize: vertical;
}

.review-anonymous-option {
  grid-template-columns: auto 1fr;
  align-items: center;
}

.order-review-form .pj-button {
  justify-self: start;
}

@media (max-width: 32rem) {
  .review-section-header {
    grid-template-columns: 1fr;
  }

  .order-review-card > header {
    flex-direction: column;
  }
}
</style>
