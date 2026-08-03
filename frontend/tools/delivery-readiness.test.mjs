import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  extractRoutePaths,
  findDeliverySourceMarkers,
  findUserVisiblePhaseLabels,
  inspectDeliveryReadiness,
  RETIRED_SELECTORS,
} from "./delivery-readiness.mjs";

test("extracts static route paths and preserves catch-all routes", () => {
  assert.deepEqual(
    extractRoutePaths(`
      { path: "/", component: Home },
      { path: '/products/:productId', component: Product },
      { path: "/:pathMatch(.*)*", component: NotFound },
    `),
    ["/", "/products/:productId", "/:pathMatch(.*)*"],
  );
});

test("detects delivery-only labels without rejecting normal product language", () => {
  assert.deepEqual(findDeliverySourceMarkers("<p>V6.4 测试入口</p>"), [
    "temporary public entry",
    "phase label exposed in product UI",
  ]);
  assert.deepEqual(
    findDeliverySourceMarkers("<p>订单结果尚未确认，请读取权威事实。</p>"),
    [],
  );
  assert.deepEqual(
    findUserVisiblePhaseLabels('displayName: "M4 Admin"'),
    ['"M4 Admin"'],
  );
  assert.deepEqual(
    findUserVisiblePhaseLabels('trackingNo: "TRACK-V642"'),
    [],
  );
  assert.ok(RETIRED_SELECTORS.includes("form-actions"));
});

test("keeps the current frontend source inside the V7 delivery baseline", async () => {
  const frontendRoot = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "..",
  );
  const result = await inspectDeliveryReadiness(frontendRoot);

  assert.deepEqual(result.violations, []);
  assert.equal(result.routePaths.storefront.length, 20);
  assert.equal(result.routePaths.admin.length, 13);
  assert.equal(result.productionVueFiles, 52);
  assert.equal(result.assets.length, 21);
  assert.equal(result.originalAssets.length, 3);
  assert.equal(result.optimizedAssets.length, 18);
  assert.equal(result.warnings.length, 0);
  assert.ok(result.totalAssetBytes > 5 * 1024 * 1024);
  assert.ok(result.totalOptimizedAssetBytes < 1024 * 1024);
});
