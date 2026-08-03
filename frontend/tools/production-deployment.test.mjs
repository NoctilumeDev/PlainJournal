import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  extractNginxBlock,
  inspectProductionDeployment,
} from "./production-deployment.mjs";

const frontendRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

test("keeps production static deployment and demo evidence complete", async () => {
  const result = await inspectProductionDeployment(frontendRoot);
  assert.deepEqual(result.violations, []);
});

test("keeps storefront and admin deployment sources inside the frontend root", async () => {
  const result = await inspectProductionDeployment(frontendRoot);
  for (const deploymentPath of Object.values(result.paths)) {
    assert.ok(deploymentPath.startsWith(frontendRoot));
  }
});

test("extracts exact Nginx location blocks without borrowing sibling rules", () => {
  const source = `
    location ^~ /assets/ {
      try_files $uri =404;
    }
    location / {
      try_files $uri $uri/ /index.html;
    }
  `;
  assert.match(
    extractNginxBlock(source, "location ^~ /assets/"),
    /try_files \$uri =404;/u,
  );
  assert.doesNotMatch(
    extractNginxBlock(source, "location ^~ /assets/"),
    /index\.html/u,
  );
});
