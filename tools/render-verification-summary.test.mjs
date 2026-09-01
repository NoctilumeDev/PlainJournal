import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  renderVerificationSummary,
  validateVerificationBaseline,
} from "./render-verification-summary.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

// Schema v2 is one sealed archival event, not a reusable current-state model.
// Keeping its identity here makes any rewrite an explicit test and schema change.
const frozenV2Identity = {
  targetRelease: "v1.0.10",
  status: "released",
  codeGateIdentity: {
    verifiedOn: "2026-08-28",
    objectRef: "refs/tags/v1.0.10",
    objectCommit: "52f7de692d26e760661c4d3172746f1ac517952c",
    sourcePath: ".github/verification-baseline.json",
    sourceBlob: "4ac8d553009c432be454e00baf22263312fcebae",
  },
  historicalRuntimeEvidence: {
    status: "FROZEN HISTORICAL EVIDENCE",
    observedFrom: "2026-07-28",
    observedThrough: "2026-08-04",
    hostBoundary: "16 GiB Windows；分组与串行拓扑",
    sourceSnapshots: [
      {
        path: "docs/evidence/m0-m8-three-layer-acceptance-20260728.md",
        objectRef: "refs/tags/v1.0.0",
        objectCommit: "d563507f16f50602e997d2272a400fa54606ff93",
        sourceCommit: "7b54cff681363555c0318621d9b8a6ad8d7edb47",
        sourceBlob: "612aa5b639ff37f2fd8f0018aa2c7e892c522120",
      },
      {
        path: "docs/evidence/v1.0.2-engineering-acceptance-20260804.md",
        objectRef: "refs/tags/v1.0.2",
        objectCommit: "da5ae5597e35089ec891e34cf49cc82f5c136298",
        sourceCommit: "7b54cff681363555c0318621d9b8a6ad8d7edb47",
        sourceBlob: "72b820bf2acab356c6b2b69b14a950958bafec48",
      },
    ],
    coreMiddleware: 7,
    representativeInstances: 3,
    capacityRequests: 1000,
    capacityConcurrency: 100,
    browserConsoleErrors: 0,
    browserNetworkErrors: 0,
  },
  freshRevalidation: {
    observedOn: "2026-08-28",
    objectCommit: "a4fecf46e918278e625c1815b16ff6fe2656ffc7",
    fixedByCommit: "1453aaaf6746700c1d75e4f9d26f28f95bc4d599",
    objectBoundary: "v1.0.10 修复前的公开提交；公开 Nacos 模板缺陷仍存在",
    evidenceClass: "SESSION READBACK SUMMARY / RAW OUTPUT NOT PERSISTED",
    runtimeAdjustment: "仅在 ignored .env 中替换 Nacos token，以继续定位宿主容量",
    status: "INCONCLUSIVE / HOST CAPACITY BOUNDARY",
    coreMiddlewareStarted: 7,
    businessJvmPortsObserved: 8,
    businessJvmPortsExpected: 8,
    minimumAvailablePhysicalMemoryGiB: 0.46,
    coreSmokeVerdict: "NOT COMPLETED",
    capacityValidation: "NOT RUN",
    representativeThreeInstanceValidation: "NOT RUN",
    failureRecoveryValidation: "NOT RUN",
    browserBusinessRevalidation: "NOT RUN",
    sourcePath: "docs/32gib-extended-validation-runbook.md",
    sourceCommit: "a42f21fec26e6973f34115e2376428a0beef9c5d",
    sourceBlob: "9d987675eb8a94601e3631e712ea01e6c774ba4e",
  },
  deferred32GiBValidation: {
    executed: false,
  },
};

function assertFrozenV2Identity(baseline) {
  const gates = baseline.codeGateEvidence;
  assert.deepEqual({
    targetRelease: baseline.targetRelease,
    status: baseline.status,
    codeGateIdentity: {
      verifiedOn: gates.verifiedOn,
      objectRef: gates.objectRef,
      objectCommit: gates.objectCommit,
      sourcePath: gates.sourcePath,
      sourceBlob: gates.sourceBlob,
    },
    historicalRuntimeEvidence: baseline.historicalRuntimeEvidence,
    freshRevalidation: baseline.freshRevalidation,
    deferred32GiBValidation: baseline.deferred32GiBValidation,
  }, frozenV2Identity);
}

function fixture() {
  return {
    schemaVersion: 2,
    targetRelease: "v1.0.10",
    status: "released",
    codeGateEvidence: {
      verifiedOn: "2026-08-28",
      objectRef: "refs/tags/v1.0.10",
      objectCommit: "1".repeat(40),
      sourcePath: ".github/verification-baseline.json",
      sourceBlob: "a".repeat(40),
      backend: {
        surefireReports: 1,
        tests: 2,
        failures: 0,
        errors: 0,
        skipped: 0,
        pmdReports: 1,
        pmdViolations: 0,
        spotbugsReports: 1,
        spotbugsPriority1: 0,
        spotbugsPriority2: 3,
        spotbugsPriority3: 4,
        lineCoverage: 72.43,
        lineCoverageMinimum: 70,
      },
      frontend: {
        unitAndContractTests: 5,
        developmentE2E: 6,
        productionE2E: 7,
        layerRules: 8,
        deliveryRules: 9,
        deploymentRules: 10,
        releaseMaterialRules: 11,
        lineCoverage: 70.06,
        lineCoverageMinimum: 70,
      },
    },
    historicalRuntimeEvidence: {
      status: "FROZEN HISTORICAL EVIDENCE",
      observedFrom: "2026-07-28",
      observedThrough: "2026-08-04",
      hostBoundary: "16 GiB Windows；分组与串行拓扑",
      sourceSnapshots: [
        {
          path: "docs/evidence/m0-m8-three-layer-acceptance-20260728.md",
          objectRef: "refs/tags/v1.0.0",
          objectCommit: "2".repeat(40),
          sourceCommit: "3".repeat(40),
          sourceBlob: "4".repeat(40),
        },
      ],
      coreMiddleware: 7,
      representativeInstances: 3,
      capacityRequests: 1000,
      capacityConcurrency: 100,
      browserConsoleErrors: 0,
      browserNetworkErrors: 0,
    },
    freshRevalidation: {
      observedOn: "2026-08-28",
      objectCommit: "5".repeat(40),
      fixedByCommit: "6".repeat(40),
      objectBoundary: "v1.0.10 修复前的公开提交",
      evidenceClass: "SESSION READBACK SUMMARY / RAW OUTPUT NOT PERSISTED",
      runtimeAdjustment: "ignored .env Nacos token replacement",
      status: "INCONCLUSIVE / HOST CAPACITY BOUNDARY",
      coreMiddlewareStarted: 7,
      businessJvmPortsObserved: 8,
      businessJvmPortsExpected: 9,
      minimumAvailablePhysicalMemoryGiB: 0.46,
      coreSmokeVerdict: "NOT COMPLETED",
      capacityValidation: "NOT RUN",
      representativeThreeInstanceValidation: "NOT RUN",
      failureRecoveryValidation: "NOT RUN",
      browserBusinessRevalidation: "NOT RUN",
      sourcePath: "docs/32gib-extended-validation-runbook.md",
      sourceCommit: "7".repeat(40),
      sourceBlob: "8".repeat(40),
    },
    deferred32GiBValidation: {
      executed: false,
    },
  };
}

function git(...args) {
  return execFileSync("git", args, {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
}

async function readRepositoryBaseline() {
  return JSON.parse(await fs.readFile(
    path.join(repositoryRoot, ".github", "verification-baseline.json"),
    "utf8",
  ));
}

test("renders versioned evidence without inventing a complete validation", () => {
  const output = renderVerificationSummary(fixture());

  assert.match(output, /2 tests/u);
  assert.match(output, /5 \/ 5/u);
  assert.match(output, /72\.43%/u);
  assert.match(output, /70\.06%/u);
  assert.match(output, /1000 请求 \/ 100 并发/u);
  assert.match(output, /FROZEN HISTORICAL EVIDENCE/u);
  assert.match(output, /INCONCLUSIVE \/ HOST CAPACITY BOUNDARY/u);
  assert.match(output, /PLANNED \/ DEFERRED/u);
  assert.match(output, /8\/9 个业务端口/u);
  assert.match(output, /证据坐标/u);
  assert.doesNotMatch(output, /最近完整验证日期/u);
});

test("renders an additive pending release without rewriting the released baseline", () => {
  const baseline = fixture();
  baseline.schemaVersion = 3;
  baseline.pendingRelease = {
    targetRelease: "v1.1.0",
    status: "release-candidate",
    verifiedOn: "2026-09-01",
    objectCommit: "9".repeat(40),
    sourcePath: "docs/frontend-layout-restructure-plan.md",
    sourceBlob: "a".repeat(40),
    runtimeEvidence: "UNCHANGED / NOT REVALIDATED",
    frontend: {
      unitAndContractTests: 327,
      developmentE2E: 61,
      productionE2E: 3,
      layerRules: 28,
      lineCoverage: 73.9,
      lineCoverageMinimum: 70,
    },
  };

  const output = renderVerificationSummary(baseline);
  assert.match(output, /v1\.0\.10.*released/su);
  assert.match(output, /v1\.1\.0.*release-candidate/su);
  assert.match(output, /327 \/ 327/u);
  assert.match(output, /UNCHANGED \/ NOT REVALIDATED/u);
});

test("rejects schema drift and contradictory evidence states", async (t) => {
  const cases = [
    ["unknown schema version", (value) => { value.schemaVersion = 999; }, /schemaVersion/u],
    [
      "missing code-gate commit",
      (value) => { delete value.codeGateEvidence.objectCommit; },
      /objectCommit/u,
    ],
    [
      "revision expression presented as a persistent ref",
      (value) => {
        value.historicalRuntimeEvidence.sourceSnapshots[0].objectRef
          = "refs/tags/v1.0.0~1";
      },
      /objectRef/u,
    ],
    [
      "PASS at an inconclusive fresh boundary",
      (value) => { value.freshRevalidation.status = "PASS"; },
      /freshRevalidation\.status/u,
    ],
    [
      "more observed ports than expected",
      (value) => { value.freshRevalidation.businessJvmPortsExpected = 7; },
      /port counts/u,
    ],
    [
      "executed deferred protocol",
      (value) => { value.deferred32GiBValidation.executed = true; },
      /executed/u,
    ],
    [
      "unknown verdict alias",
      (value) => { value.freshRevalidation.result = "PASS"; },
      /unknown fields: result/u,
    ],
    [
      "reversed historical date range",
      (value) => {
        value.historicalRuntimeEvidence.observedFrom = "2026-08-05";
        value.historicalRuntimeEvidence.observedThrough = "2026-08-04";
      },
      /observedFrom/u,
    ],
    [
      "historical evidence dated after fresh revalidation",
      (value) => { value.historicalRuntimeEvidence.observedThrough = "2026-08-29"; },
      /must end on or before/u,
    ],
    [
      "fresh revalidation dated after release gates",
      (value) => { value.codeGateEvidence.verifiedOn = "2026-08-27"; },
      /must not follow/u,
    ],
    [
      "unrelated fresh source path",
      (value) => { value.freshRevalidation.sourcePath = "docs/README.md"; },
      /sourcePath/u,
    ],
    [
      "historical source without a blob identity",
      (value) => { delete value.historicalRuntimeEvidence.sourceSnapshots[0].sourceBlob; },
      /sourceBlob/u,
    ],
  ];

  for (const [name, mutate, expected] of cases) {
    await t.test(name, () => {
      const baseline = fixture();
      mutate(baseline);
      assert.throws(() => renderVerificationSummary(baseline), expected);
    });
  }
});

test("keeps the schema v2 archival event sealed against in-place rewrites", async (t) => {
  const baseline = await readRepositoryBaseline();
  const cases = [
    [
      "substituted release-gate source",
      (value) => {
        value.codeGateEvidence.sourceBlob
          = "90c6f04d2b9b0aecd398257fd96f32f226df0898";
        value.codeGateEvidence.frontend.unitAndContractTests = 319;
        value.codeGateEvidence.frontend.lineCoverage = 70.06;
      },
    ],
    [
      "rewritten 16 GiB fresh boundary",
      (value) => {
        value.freshRevalidation.minimumAvailablePhysicalMemoryGiB = 32;
        value.freshRevalidation.coreMiddlewareStarted = 0;
        value.freshRevalidation.businessJvmPortsObserved = 1;
        value.freshRevalidation.businessJvmPortsExpected = 1;
      },
    ],
    [
      "rewritten historical capacity",
      (value) => {
        value.historicalRuntimeEvidence.hostBoundary = "32 GiB Windows full topology";
        value.historicalRuntimeEvidence.capacityRequests = 999999;
        value.historicalRuntimeEvidence.representativeInstances = 99;
      },
    ],
    [
      "unrelated historical source",
      (value) => {
        const source = value.historicalRuntimeEvidence.sourceSnapshots[0];
        source.path = "docs/README.md";
        source.objectRef = "refs/tags/v1.0.10";
        source.objectCommit = "52f7de692d26e760661c4d3172746f1ac517952c";
        source.sourceCommit = "37009ea78ecf268cf9462f1499b5b6717faa8821";
        source.sourceBlob = "7858fd0123a37c162b0631d9a3b0c237bc7155bc";
      },
    ],
    [
      "ordinary documentation commit presented as the Nacos fix",
      (value) => {
        value.freshRevalidation.fixedByCommit
          = "a42f21fec26e6973f34115e2376428a0beef9c5d";
      },
    ],
    [
      "downgraded canonical runbook",
      (value) => {
        value.freshRevalidation.sourceCommit
          = "1ed9e22594c6e16e4476f7635189cb4b3d407615";
        value.freshRevalidation.sourceBlob
          = "fdd8bbe7f6cfb2ec0f7a3c4d27a14bcf377f5418";
      },
    ],
  ];

  for (const [name, mutate] of cases) {
    await t.test(name, () => {
      const candidate = structuredClone(baseline);
      mutate(candidate);
      assert.throws(() => assertFrozenV2Identity(candidate));
    });
  }
});

test("binds the repository baseline to reachable immutable Git objects", async () => {
  const baseline = await readRepositoryBaseline();
  validateVerificationBaseline(baseline);
  assertFrozenV2Identity(baseline);

  const gates = baseline.codeGateEvidence;
  assert.ok(git("show-ref", "--verify", "--hash", gates.objectRef));
  assert.equal(git("rev-parse", `${gates.objectRef}^{commit}`), gates.objectCommit);
  assert.equal(
    git("rev-parse", `${gates.objectCommit}:${gates.sourcePath}`),
    gates.sourceBlob,
  );
  const gateSource = JSON.parse(git("show", `${gates.objectCommit}:${gates.sourcePath}`));
  assert.equal(baseline.targetRelease, gateSource.targetRelease);
  assert.equal(baseline.status, gateSource.status);
  assert.equal(gates.verifiedOn, gateSource.verifiedOn);
  assert.deepEqual(gates.backend, gateSource.backend);
  assert.deepEqual(gates.frontend, gateSource.frontend);

  const pending = baseline.pendingRelease;
  assert.equal(baseline.schemaVersion, 3);
  assert.ok(pending);
  assert.equal(git("rev-parse", `${pending.objectCommit}^{commit}`), pending.objectCommit);
  assert.doesNotThrow(() => git(
    "merge-base",
    "--is-ancestor",
    pending.objectCommit,
    "main",
  ));
  assert.equal(
    git("rev-parse", `${pending.objectCommit}:${pending.sourcePath}`),
    pending.sourceBlob,
  );
  const pendingSource = git("show", `${pending.objectCommit}:${pending.sourcePath}`);
  assert.match(
    pendingSource,
    new RegExp(pending.targetRelease.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&"), "u"),
  );
  assert.match(pendingSource, /327 条单元测试/u);
  assert.match(pendingSource, /61 条开发 E2E/u);
  assert.match(pendingSource, /73\.9%/u);

  const historicalSources = [];
  for (const snapshot of baseline.historicalRuntimeEvidence.sourceSnapshots) {
    assert.ok(git("show-ref", "--verify", "--hash", snapshot.objectRef));
    assert.equal(git("rev-parse", `${snapshot.objectRef}^{commit}`), snapshot.objectCommit);
    assert.equal(
      git("rev-parse", `${snapshot.sourceCommit}:${snapshot.path}`),
      snapshot.sourceBlob,
    );
    historicalSources.push(git("show", `${snapshot.sourceCommit}:${snapshot.path}`));
  }
  const historicalText = historicalSources.join("\n");
  assert.match(historicalText, /1000 请求\/100 并发/u);
  assert.match(historicalText, /最多 3 同服务实例/u);
  assert.match(historicalText, /七个核心\s+中间件/u);
  assert.match(historicalText, /Console、非预期 HTTP 和网络失败均为 0/u);

  const fresh = baseline.freshRevalidation;
  assert.equal(git("rev-parse", `${fresh.objectCommit}^{commit}`), fresh.objectCommit);
  assert.equal(git("rev-parse", `${fresh.fixedByCommit}^{commit}`), fresh.fixedByCommit);
  assert.notEqual(fresh.objectCommit, fresh.fixedByCommit);
  assert.notEqual(fresh.fixedByCommit, gates.objectCommit);
  const [, firstParent] = git(
    "rev-list",
    "--parents",
    "-n",
    "1",
    fresh.fixedByCommit,
  ).split(/\s+/u);
  assert.equal(firstParent, fresh.objectCommit);
  assert.doesNotThrow(() => git(
    "merge-base",
    "--is-ancestor",
    fresh.objectCommit,
    fresh.fixedByCommit,
  ));
  assert.doesNotThrow(() => git(
    "merge-base",
    "--is-ancestor",
    fresh.fixedByCommit,
    gates.objectCommit,
  ));
  assert.equal(
    git("rev-parse", `${fresh.sourceCommit}:${fresh.sourcePath}`),
    fresh.sourceBlob,
  );
  const freshSource = git("show", `${fresh.sourceCommit}:${fresh.sourcePath}`);
  assert.match(freshSource, new RegExp(fresh.objectCommit, "u"));
  assert.match(
    freshSource,
    /\| 八个业务 JVM 全部监听后 \| `8\/8` 服务端口存在；宿主仅余 `0\.46 GiB`/u,
  );
  assert.match(freshSource, /七个核心中间件与 bootstrap 完成后/u);
  assert.match(freshSource, new RegExp(fresh.status.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&"), "u"));

  const freshEnvironment = git(
    "show",
    `${fresh.objectCommit}:deploy/docker/.env.example`,
  );
  const fixedEnvironment = git(
    "show",
    `${fresh.fixedByCommit}:deploy/docker/.env.example`,
  );
  assert.match(
    freshEnvironment,
    /^NACOS_AUTH_TOKEN=replace-with-base64-token-longer-than-32-bytes$/mu,
  );
  assert.doesNotMatch(
    fixedEnvironment,
    /^NACOS_AUTH_TOKEN=replace-with-base64-token-longer-than-32-bytes$/mu,
  );
  assert.match(
    fixedEnvironment,
    /^# NACOS_AUTH_TOKEN is generated into the ignored \.env before Compose starts\.$/mu,
  );
  assert.match(
    git("show", `${fresh.fixedByCommit}:deploy/docker/bootstrap-resources.ps1`),
    /function Ensure-NacosAuthToken/u,
  );

  const deferred = baseline.deferred32GiBValidation;
  assert.equal(deferred.executed, false);
  assert.match(
    freshSource,
    /状态：`PLANNED \/ DEFERRED`[\s\S]*当前结论：本协议尚未执行/u,
  );
});
