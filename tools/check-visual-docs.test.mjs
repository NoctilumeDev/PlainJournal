import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { functionalModules } from "../docs/visuals/data/functional-modules.data.js";
import { systemArchitecture } from "../docs/visuals/data/system-architecture.data.js";
import { inspectVisualDocs } from "./check-visual-docs.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("keeps visual architecture aligned with real applications", async () => {
  const result = await inspectVisualDocs(repositoryRoot);
  assert.deepEqual(result.violations, []);
  assert.equal(result.applications, 11);
  assert.equal(result.frontends, 2);
  assert.equal(result.gatewayRoutes, 10);
  assert.equal(result.ownerSchemas, 10);
});

test("rejects a visual owner schema that differs from service configuration", async () => {
  const altered = structuredClone(systemArchitecture);
  altered.serviceGroups[0].services[0].owner = "ecom_wrong_identity";
  const result = await inspectVisualDocs(repositoryRoot, altered, functionalModules);
  assert.ok(
    result.violations.includes(
      "schema ownership for identity-service is ecom_identity, not ecom_wrong_identity",
    ),
  );
});

test("rejects a frontend omitted from the system architecture", async () => {
  const altered = structuredClone(systemArchitecture);
  altered.experiences = altered.experiences.filter((item) => item.id !== "admin-web");
  const result = await inspectVisualDocs(repositoryRoot, altered, functionalModules);
  assert.ok(
    result.violations.includes("system architecture frontends is missing admin-web"),
  );
});

test("rejects a functional entrance that names an unknown frontend", async () => {
  const altered = structuredClone(functionalModules);
  altered.entrances[0].subtitle = "unknown-web";
  const result = await inspectVisualDocs(repositoryRoot, systemArchitecture, altered);
  assert.ok(
    result.violations.includes("functional module frontends contains unknown unknown-web"),
  );
});

test("rejects functional modules that reference an unknown service", async () => {
  const altered = structuredClone(functionalModules);
  altered.modules[0].collaborators.push("unknown-service");
  const result = await inspectVisualDocs(repositoryRoot, systemArchitecture, altered);
  assert.ok(
    result.violations.includes("functional modules reference unknown unknown-service"),
  );
});

test("rejects a service omitted from the gateway topology", async () => {
  const altered = structuredClone(systemArchitecture);
  altered.gatewayTargets = altered.gatewayTargets.filter((item) => item !== "analytics-service");
  const result = await inspectVisualDocs(repositoryRoot, altered, functionalModules);
  assert.ok(
    result.violations.includes("gateway topology is missing analytics-service"),
  );
});

test("rejects duplicate service targets in the gateway topology", async () => {
  const altered = structuredClone(systemArchitecture);
  altered.gatewayTargets[0] = "analytics-service";
  const result = await inspectVisualDocs(repositoryRoot, altered, functionalModules);
  assert.ok(
    result.violations.includes("gateway topology contains duplicate service targets"),
  );
});

test("rejects gateway topology that differs from the real route table", async () => {
  const altered = structuredClone(systemArchitecture);
  altered.gatewayTargets = altered.gatewayTargets
    .filter((item) => item !== "analytics-service")
    .concat("unknown-service");
  const result = await inspectVisualDocs(repositoryRoot, altered, functionalModules);
  assert.ok(result.violations.includes("gateway routes is missing analytics-service"));
  assert.ok(result.violations.includes("gateway routes contains unknown unknown-service"));
});

test("rejects a module omitted from the functional module tree", async () => {
  const altered = structuredClone(functionalModules);
  altered.entrances[0].domains[0].moduleIds = ["identity-account"];
  const result = await inspectVisualDocs(repositoryRoot, systemArchitecture, altered);
  assert.ok(
    result.violations.includes("functional module tree is missing catalog-discovery"),
  );
});
