import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { inspectBackendBoundaries } from "./check-backend-boundaries.mjs";

const domains = [
  "analytics",
  "catalog",
  "chat",
  "fulfillment",
  "identity",
  "inventory",
  "marketing",
  "notification",
  "payment",
  "trade",
];

async function createRepository() {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "plainjournal-boundaries-"));
  for (const domain of domains) {
    const javaRoot = path.join(
      root,
      "backend",
      "services",
      `${domain}-service`,
      "src",
      "main",
      "java",
      "com",
      "ecommerce",
      domain,
    );
    await fs.mkdir(javaRoot, { recursive: true });
    await fs.writeFile(
      path.join(javaRoot, `${domain}Service.java`),
      `package com.ecommerce.${domain};\n`,
      "utf8",
    );
    await fs.writeFile(
      path.join(root, "backend", "services", `${domain}-service`, "pom.xml"),
      `<project><dependencies></dependencies></project>\n`,
      "utf8",
    );
  }
  await fs.mkdir(
    path.join(root, "backend", "ecommerce-gateway", "src", "main", "java"),
    { recursive: true },
  );
  await fs.mkdir(
    path.join(
      root,
      "backend",
      "platform-common",
      "src",
      "main",
      "java",
      "com",
      "ecommerce",
      "platform",
      "common",
      "api",
    ),
    { recursive: true },
  );
  await fs.writeFile(
    path.join(
      root,
      "backend",
      "platform-common",
      "src",
      "main",
      "java",
      "com",
      "ecommerce",
      "platform",
      "common",
      "api",
      "ApiResponse.java",
    ),
    "package com.ecommerce.platform.common.api;\n",
    "utf8",
  );
  return root;
}

test("accepts isolated service packages", async () => {
  const root = await createRepository();
  const result = await inspectBackendBoundaries(root);
  assert.deepEqual(result.violations, []);
});

test("rejects cross-service imports and controller mapper access", async () => {
  const root = await createRepository();
  const controller = path.join(
    root,
    "backend",
    "services",
    "trade-service",
    "src",
    "main",
    "java",
    "com",
    "ecommerce",
    "trade",
    "TradeController.java",
  );
  await fs.writeFile(
    controller,
    [
      "package com.ecommerce.trade;",
      "import com.ecommerce.payment.application.PaymentService;",
      "import com.ecommerce.trade.infrastructure.persistence.mapper.OrderMapper;",
      "",
    ].join("\n"),
    "utf8",
  );

  const result = await inspectBackendBoundaries(root);
  assert.equal(result.violations.length, 2);
});
test("rejects persistence and domain packages in platform-common", async () => {
  const root = await createRepository();
  const entityRoot = path.join(
    root,
    "backend",
    "platform-common",
    "src",
    "main",
    "java",
    "com",
    "ecommerce",
    "platform",
    "common",
    "order",
  );
  await fs.mkdir(entityRoot, { recursive: true });
  await fs.writeFile(
    path.join(entityRoot, "SharedOrder.java"),
    [
      "package com.ecommerce.platform.common.order;",
      "import jakarta.persistence.Entity;",
      "@Entity class SharedOrder {}",
      "",
    ].join("\n"),
    "utf8",
  );

  const result = await inspectBackendBoundaries(root);
  assert.equal(result.violations.length, 2);
});
