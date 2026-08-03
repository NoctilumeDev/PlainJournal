import assert from "node:assert/strict";
import test from "node:test";

import {
  extractRelativeImports,
  validateLayerEdge,
  validatePublicEntry,
  validateVisualTokenOwnership,
} from "./layer-boundaries.mjs";

test("allows downward composition and same feature slice imports", () => {
  assert.equal(
    validateLayerEdge("app/router.ts", "features/theme/model/theme.ts"),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "features/theme/ui/ThemePreference.vue",
      "features/theme/model/theme.ts",
    ),
    null,
  );
});

test("rejects upward and cross-feature imports", () => {
  assert.match(
    validateLayerEdge("shared/ui/Button.vue", "features/theme/model/theme.ts"),
    /higher features layer/u,
  );
  assert.match(
    validateLayerEdge(
      "features/theme/ui/ThemePreference.vue",
      "features/checkout/model/checkout.ts",
    ),
    /cannot reach across/u,
  );
});

test("keeps legacy dependencies behind the app migration seam", () => {
  assert.match(
    validateLayerEdge("features/theme/model/theme.ts", "stores/session.ts"),
    /legacy stores/u,
  );
  assert.equal(
    validateLayerEdge("app/AppHeader.vue", "stores/session.ts"),
    null,
  );
});

test("requires feature consumers to use the slice public entry", () => {
  assert.equal(
    validatePublicEntry("views/GlobalIndexView.vue", "features/theme"),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/GlobalIndexView.vue",
      "features/theme/model/theme.ts",
    ),
    /public index/u,
  );
  assert.equal(
    validatePublicEntry(
      "features/theme/ui/ThemePreference.vue",
      "features/theme/model/theme.ts",
    ),
    null,
  );
});

test("requires shared consumers to use a public slice entry", () => {
  assert.equal(
    validatePublicEntry("pages/home/HomePage.vue", "shared/ui"),
    null,
  );
  assert.equal(
    validatePublicEntry("main.ts", "shared/ui/styles.css"),
    null,
  );
  assert.match(
    validatePublicEntry("pages/home/HomePage.vue", "shared/ui/AsyncState.vue"),
    /public index/u,
  );
});

test("keeps session orchestration above guest bag state and behind its public entry", () => {
  assert.equal(
    validateLayerEdge(
      "features/customer-session/model/session.ts",
      "entities/guest-bag/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "pages/identity/LoginPage.vue",
      "features/customer-session/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/identity/LoginPage.vue",
      "features/customer-session/model/session.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/guest-bag/model/guestBag.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps address facts below page composition and independent from session orchestration", () => {
  assert.equal(
    validateLayerEdge(
      "pages/account/AddressManagementPage.vue",
      "entities/address/index.ts",
    ),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "pages/account/AddressManagementPage.vue",
      "features/customer-session/index.ts",
    ),
    null,
  );
  assert.match(
    validateLayerEdge(
      "entities/address/model/addressStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
  assert.equal(
    validatePublicEntry(
      "pages/account/AddressManagementPage.vue",
      "entities/address/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/account/AddressManagementPage.vue",
      "entities/address/model/addressStore.ts",
    ),
    /public index/u,
  );
});

test("keeps benefit facts below page composition and independent from session orchestration", () => {
  assert.equal(
    validateLayerEdge(
      "views/BenefitCenterView.vue",
      "entities/benefit/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/BenefitCenterView.vue",
      "entities/benefit/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/BenefitCenterView.vue",
      "entities/benefit/model/benefitStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/benefit/model/benefitStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps Notification facts below account composition and independent from session orchestration", () => {
  assert.equal(
    validateLayerEdge(
      "pages/account/NotificationCenterPage.vue",
      "entities/notification/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "pages/account/NotificationCenterPage.vue",
      "entities/notification/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/account/NotificationCenterPage.vue",
      "entities/notification/model/notificationStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/notification/model/notificationStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps customer Chat facts below support composition and independent from session orchestration", () => {
  assert.equal(
    validateLayerEdge(
      "views/SupportChatView.vue",
      "entities/chat/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/SupportChatView.vue",
      "entities/chat/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/SupportChatView.vue",
      "entities/chat/model/customerChatStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/chat/model/customerChatStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps admin Governance commands behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/GovernanceWorkspaceView.vue",
      "entities/governance/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/GovernanceWorkspaceView.vue",
      "entities/governance/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/GovernanceWorkspaceView.vue",
      "entities/governance/model/governanceStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/governance/model/governanceStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Fulfillment facts and commands behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/FulfillmentWorkspaceView.vue",
      "entities/admin-fulfillment/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/FulfillmentWorkspaceView.vue",
      "entities/admin-fulfillment/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/FulfillmentWorkspaceView.vue",
      "entities/admin-fulfillment/model/adminFulfillmentStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-fulfillment/model/adminFulfillmentStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Inventory facts and commands behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/InventoryWorkspaceView.vue",
      "entities/admin-inventory/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/InventoryWorkspaceView.vue",
      "entities/admin-inventory/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/InventoryWorkspaceView.vue",
      "entities/admin-inventory/model/adminInventoryStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-inventory/model/adminInventoryStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Marketing facts and commands behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/MarketingWorkspaceView.vue",
      "entities/admin-marketing/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/MarketingWorkspaceView.vue",
      "entities/admin-marketing/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/MarketingWorkspaceView.vue",
      "entities/admin-marketing/model/adminMarketingStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-marketing/model/adminMarketingStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Catalog public projection behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/CatalogReadOnlyView.vue",
      "entities/admin-catalog/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/CatalogReadOnlyView.vue",
      "entities/admin-catalog/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/CatalogReadOnlyView.vue",
      "entities/admin-catalog/model/adminCatalogStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-catalog/model/adminCatalogStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin After-sale facts and review recovery behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/AfterSaleWorkspaceView.vue",
      "entities/admin-after-sale/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/AfterSaleWorkspaceView.vue",
      "entities/admin-after-sale/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/AfterSaleWorkspaceView.vue",
      "entities/admin-after-sale/model/adminAfterSaleStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-after-sale/model/adminAfterSaleStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Review facts and idempotent governance behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/ReviewWorkspaceView.vue",
      "entities/admin-review/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/ReviewWorkspaceView.vue",
      "entities/admin-review/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/ReviewWorkspaceView.vue",
      "entities/admin-review/model/adminReviewStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-review/model/adminReviewStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Chat membership, messages and recovery behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/ChatWorkspaceView.vue",
      "entities/admin-chat/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/ChatWorkspaceView.vue",
      "entities/admin-chat/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/ChatWorkspaceView.vue",
      "entities/admin-chat/model/adminChatStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-chat/model/adminChatStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps admin Analytics projection behind an entity boundary", () => {
  assert.equal(
    validateLayerEdge(
      "views/OperationsHomeView.vue",
      "entities/admin-analytics/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/OperationsHomeView.vue",
      "entities/admin-analytics/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/OperationsHomeView.vue",
      "entities/admin-analytics/model/adminAnalyticsStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/admin-analytics/model/adminAnalyticsStore.ts",
      "stores/session.ts",
    ),
    /legacy stores/u,
  );
});

test("keeps account-cart facts below bag composition and independent from live session state", () => {
  assert.equal(
    validateLayerEdge(
      "pages/bag/BagPage.vue",
      "entities/account-cart/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "pages/bag/BagPage.vue",
      "entities/account-cart/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/bag/BagPage.vue",
      "entities/account-cart/model/accountCartStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/account-cart/model/accountCartStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps checkout orchestration behind its public entry and below page composition", () => {
  assert.equal(
    validateLayerEdge(
      "pages/checkout/CheckoutPage.vue",
      "features/checkout/index.ts",
    ),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "features/checkout/model/checkoutStore.ts",
      "entities/account-cart/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "pages/checkout/CheckoutPage.vue",
      "features/checkout/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/checkout/CheckoutPage.vue",
      "features/checkout/model/checkoutStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "features/checkout/model/checkoutStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /cannot reach across/u,
  );
});

test("keeps Trade order facts below pages and independent from live session state", () => {
  assert.equal(
    validateLayerEdge(
      "pages/orders/OrderListPage.vue",
      "entities/order/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "pages/orders/OrderListPage.vue",
      "entities/order/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "pages/orders/OrderListPage.vue",
      "entities/order/model/orderStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/order/model/orderStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps Payment workflow behind its feature entry and above Trade order facts", () => {
  assert.equal(
    validateLayerEdge(
      "features/order-payment/ui/OrderPaymentSection.vue",
      "entities/order/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/OrderDetailView.vue",
      "features/order-payment/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/OrderDetailView.vue",
      "features/order-payment/model/paymentStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "features/order-payment/model/paymentStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /cannot reach across/u,
  );
});

test("keeps Fulfillment facts isolated below the receipt-confirmation feature", () => {
  assert.equal(
    validateLayerEdge(
      "features/order-fulfillment/model/receiptConfirmationStore.ts",
      "entities/fulfillment/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/OrderDetailView.vue",
      "features/order-fulfillment/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/OrderDetailView.vue",
      "features/order-fulfillment/model/receiptConfirmationStore.ts",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/fulfillment/model/fulfillmentStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
  assert.match(
    validateLayerEdge(
      "features/order-fulfillment/model/receiptConfirmationStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /cannot reach across/u,
  );
});

test("keeps the after-sale journey above three isolated owner-domain facts", () => {
  assert.equal(
    validateLayerEdge(
      "features/after-sale-workflow/ui/AfterSaleWorkspace.vue",
      "entities/after-sale/index.ts",
    ),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "features/after-sale-workflow/ui/AfterSaleWorkspace.vue",
      "entities/return-receipt/index.ts",
    ),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "features/after-sale-workflow/ui/AfterSaleWorkspace.vue",
      "entities/refund/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/AfterSaleDetailView.vue",
      "features/after-sale-workflow/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/AfterSaleDetailView.vue",
      "features/after-sale-workflow/ui/AfterSaleWorkspace.vue",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/after-sale/model/afterSaleStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("keeps review participation and order intent above one Catalog review owner", () => {
  assert.equal(
    validateLayerEdge(
      "features/product-reviews/ui/ProductReviewsSection.vue",
      "entities/product-review/index.ts",
    ),
    null,
  );
  assert.equal(
    validateLayerEdge(
      "features/order-review/ui/OrderReviewSection.vue",
      "entities/product-review/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/ProductDetailView.vue",
      "features/product-reviews/index.ts",
    ),
    null,
  );
  assert.equal(
    validatePublicEntry(
      "views/OrderDetailView.vue",
      "features/order-review/index.ts",
    ),
    null,
  );
  assert.match(
    validatePublicEntry(
      "views/ProductDetailView.vue",
      "features/product-reviews/ui/ProductReviewsSection.vue",
    ),
    /public index/u,
  );
  assert.match(
    validateLayerEdge(
      "entities/product-review/model/productReviewStore.ts",
      "features/customer-session/model/session.ts",
    ),
    /higher features layer/u,
  );
});

test("extracts static and dynamic relative imports", () => {
  const imports = extractRelativeImports(`
    import Widget from "../shared/ui/Widget.vue";
    export { value } from "./model/value";
    const page = import("../pages/HomePage.vue");
    import { ref } from "vue";
  `);
  assert.deepEqual(
    imports.sort(),
    ["../pages/HomePage.vue", "../shared/ui/Widget.vue", "./model/value"],
  );
});

test("keeps raw colors and foundation palette tokens inside the design-system", () => {
  assert.deepEqual(
    validateVisualTokenOwnership(
      "packages/design-system/src/tokens.css",
      ":root { --pj-palette-lotus-500: #6f8f8b; }",
    ),
    [],
  );
  assert.match(
    validateVisualTokenOwnership(
      "packages/ui/src/styles.css",
      ".button { color: var(--pj-palette-lotus-500); }",
    )[0] ?? "",
    /foundation palette token/u,
  );
  assert.match(
    validateVisualTokenOwnership(
      "storefront-web/src/pages/HomePage.vue",
      "<style>.hero { color: #6f8f8b; }</style>",
    )[0] ?? "",
    /raw color literal/u,
  );
});
