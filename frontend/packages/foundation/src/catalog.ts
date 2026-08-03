import type { ApiClient, BusinessId, PageResponse } from "./api";

export interface Category {
  id: BusinessId;
  parentId: BusinessId | null;
  name: string;
  slug: string;
  sortOrder: number;
}

export interface Brand {
  id: BusinessId;
  name: string;
  slug: string;
}

export interface ProductSummary {
  id: BusinessId;
  title: string;
  subtitle: string | null;
  category: Category;
  brand: Brand;
  minimumPrice: string | number;
  coverUrl: string | null;
}

export interface ProductSku {
  id: BusinessId;
  skuCode: string;
  name: string;
  specJson: string;
  salePrice: string | number;
  marketPrice: string | number | null;
  status: "ACTIVE" | "INACTIVE";
  version: number;
}

export interface ProductMedia {
  id: BusinessId;
  skuId: BusinessId | null;
  objectKey: string;
  mimeType: string;
  sizeBytes: number;
  sortOrder: number;
  url: string | null;
}

export interface ProductDetail {
  id: BusinessId;
  title: string;
  subtitle: string | null;
  description: string | null;
  status: string;
  version: number;
  category: Category;
  brand: Brand;
  skus: ProductSku[];
  media: ProductMedia[];
}

export interface ProductQuery {
  page?: number;
  size?: number;
  categoryId?: BusinessId;
  keyword?: string;
}

export interface ProductSearchQuery {
  q: string;
  page?: number;
  size?: number;
  categoryId?: BusinessId;
}

export interface ProductSearchPage {
  items: ProductSummary[];
  page: number;
  size: number;
  matchedTotal: number;
  source: "OPENSEARCH" | "MYSQL_FALLBACK";
  degraded: boolean;
}

export interface ReviewEligibility {
  id: BusinessId;
  orderNo: string;
  lineNo: number;
  productId: BusinessId;
  skuId: BusinessId;
  productTitle: string;
  skuCode: string;
  skuName: string;
  specJson: string;
  imageObjectKey: string | null;
  quantity: number;
  status: "ELIGIBLE" | "REVIEWED";
  reviewId: BusinessId | null;
  completedAt: string;
}

export interface ReviewReply {
  id: BusinessId;
  content: string;
  createdAt: string;
}

export interface ProductReview {
  id: BusinessId;
  productId: BusinessId;
  skuId: BusinessId;
  skuName: string;
  specJson: string;
  rating: number;
  content: string;
  anonymous: boolean;
  authorLabel: string;
  status: "PUBLISHED" | "HIDDEN";
  likeCount: number;
  likedByViewer: boolean;
  reply: ReviewReply | null;
  createdAt: string;
}

export interface ReviewSummary {
  productId: BusinessId;
  reviewCount: number;
  averageRating: string | number;
  rating1Count: number;
  rating2Count: number;
  rating3Count: number;
  rating4Count: number;
  rating5Count: number;
}

export interface CreateReviewInput {
  eligibilityId: BusinessId;
  rating: number;
  content: string;
  anonymous: boolean;
}

export type ReviewReportReason =
  | "SPAM"
  | "ABUSE"
  | "FALSE_INFORMATION"
  | "OTHER";

export interface ReportReviewInput {
  reasonCode: ReviewReportReason;
  detail?: string;
}

export interface ReviewReportReceipt {
  id: BusinessId;
  reviewId: BusinessId;
  status: "OPEN" | "RESOLVED";
  createdAt: string;
}

export interface ReviewReport {
  id: BusinessId;
  reviewId: BusinessId;
  productId: BusinessId;
  rating: number;
  reviewContent: string;
  reasonCode: ReviewReportReason;
  detail: string | null;
  status: "OPEN" | "RESOLVED";
  resolution: "UPHELD" | "REJECTED" | null;
  createdAt: string;
  resolvedAt: string | null;
}

export interface ReviewModerationResult {
  reportId: BusinessId;
  reviewId: BusinessId;
  commandId: string;
  resolution: "UPHELD" | "REJECTED";
  reviewStatusBefore: "PUBLISHED" | "HIDDEN";
  reviewStatusAfter: "PUBLISHED" | "HIDDEN";
  resolvedAt: string;
}

export interface CatalogApi {
  listCategories(): Promise<Category[]>;
  listProducts(query?: ProductQuery): Promise<PageResponse<ProductSummary>>;
  searchProducts(query: ProductSearchQuery): Promise<ProductSearchPage>;
  getProduct(productId: BusinessId): Promise<ProductDetail>;
  reviewSummary(productId: BusinessId): Promise<ReviewSummary>;
  productReviews(
    productId: BusinessId,
    page?: number,
    size?: number,
  ): Promise<PageResponse<ProductReview>>;
  reviewEligibilities(orderNo?: string): Promise<ReviewEligibility[]>;
  createReview(input: CreateReviewInput, idempotencyKey: string): Promise<ProductReview>;
  likeReview(reviewId: BusinessId): Promise<ProductReview>;
  unlikeReview(reviewId: BusinessId): Promise<ProductReview>;
  reportReview(
    reviewId: BusinessId,
    input: ReportReviewInput,
  ): Promise<ReviewReportReceipt>;
  adminReviewReports(
    status?: "OPEN" | "RESOLVED",
    page?: number,
    size?: number,
  ): Promise<PageResponse<ReviewReport>>;
  replyReview(
    reviewId: BusinessId,
    content: string,
    commandId: string,
  ): Promise<ProductReview>;
  resolveReviewReport(
    reportId: BusinessId,
    input: {
      commandId: string;
      resolution: "UPHELD" | "REJECTED";
      reason: string;
    },
  ): Promise<ReviewModerationResult>;
}

export function createCatalogApi(client: ApiClient): CatalogApi {
  return {
    listCategories() {
      return client.request<Category[]>("/api/v1/catalog/categories");
    },
    listProducts(query: ProductQuery = {}) {
      const search = new URLSearchParams({
        page: String(query.page ?? 1),
        size: String(query.size ?? 20),
      });
      if (query.categoryId) {
        search.set("categoryId", query.categoryId);
      }
      if (query.keyword?.trim()) {
        search.set("keyword", query.keyword.trim());
      }
      return client.request<PageResponse<ProductSummary>>(
        `/api/v1/catalog/products?${search.toString()}`,
      );
    },
    searchProducts(query: ProductSearchQuery) {
      const search = new URLSearchParams({
        q: query.q.trim(),
        page: String(query.page ?? 1),
        size: String(query.size ?? 20),
      });
      if (query.categoryId) {
        search.set("categoryId", query.categoryId);
      }
      return client.request<ProductSearchPage>(
        `/api/v1/catalog/search/products?${search.toString()}`,
      );
    },
    getProduct(productId: BusinessId) {
      return client.request<ProductDetail>(
        `/api/v1/catalog/products/${encodeURIComponent(productId)}`,
      );
    },
    reviewSummary(productId: BusinessId) {
      return client.request<ReviewSummary>(
        `/api/v1/catalog/products/${encodeURIComponent(productId)}/review-summary`,
      );
    },
    productReviews(productId: BusinessId, page = 1, size = 20) {
      const search = new URLSearchParams({
        page: String(page),
        size: String(size),
      });
      return client.request<PageResponse<ProductReview>>(
        `/api/v1/catalog/products/${encodeURIComponent(productId)}/reviews?${search.toString()}`,
      );
    },
    reviewEligibilities(orderNo?: string) {
      const search = new URLSearchParams();
      if (orderNo?.trim()) {
        search.set("orderNo", orderNo.trim());
      }
      const suffix = search.size > 0 ? `?${search.toString()}` : "";
      return client.request<ReviewEligibility[]>(
        `/api/v1/catalog/review-eligibilities${suffix}`,
      );
    },
    createReview(input: CreateReviewInput, idempotencyKey: string) {
      return client.request<ProductReview>("/api/v1/catalog/reviews", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(input),
      });
    },
    likeReview(reviewId: BusinessId) {
      return client.request<ProductReview>(
        `/api/v1/catalog/reviews/${encodeURIComponent(reviewId)}/likes`,
        { method: "POST" },
      );
    },
    unlikeReview(reviewId: BusinessId) {
      return client.request<ProductReview>(
        `/api/v1/catalog/reviews/${encodeURIComponent(reviewId)}/likes`,
        { method: "DELETE" },
      );
    },
    reportReview(reviewId: BusinessId, input: ReportReviewInput) {
      return client.request<ReviewReportReceipt>(
        `/api/v1/catalog/reviews/${encodeURIComponent(reviewId)}/reports`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
    adminReviewReports(status?: "OPEN" | "RESOLVED", page = 1, size = 20) {
      const search = new URLSearchParams({
        page: String(page),
        size: String(size),
      });
      if (status) {
        search.set("status", status);
      }
      return client.request<PageResponse<ReviewReport>>(
        `/api/v1/catalog/admin/reviews/reports?${search.toString()}`,
      );
    },
    replyReview(reviewId: BusinessId, content: string, commandId: string) {
      return client.request<ProductReview>(
        `/api/v1/catalog/admin/reviews/${encodeURIComponent(reviewId)}/reply`,
        {
          method: "POST",
          headers: { "Idempotency-Key": commandId },
          body: JSON.stringify({ content }),
        },
      );
    },
    resolveReviewReport(
      reportId: BusinessId,
      input: {
        commandId: string;
        resolution: "UPHELD" | "REJECTED";
        reason: string;
      },
    ) {
      return client.request<ReviewModerationResult>(
        `/api/v1/catalog/admin/reviews/reports/${encodeURIComponent(reportId)}/resolve`,
        {
          method: "POST",
          body: JSON.stringify(input),
        },
      );
    },
  };
}
