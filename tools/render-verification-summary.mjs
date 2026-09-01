import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const FULL_SHA = /^[0-9a-f]{40}$/u;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;
const STABLE_RELEASE = /^v\d+\.\d+\.\d+$/u;
const PERSISTENT_REF = /^refs\/(?:heads|tags)\/[A-Za-z0-9][A-Za-z0-9._/-]*$/u;

function invalid(message) {
  throw new TypeError(`Invalid verification baseline: ${message}`);
}

function requireRecord(value, name) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    invalid(`${name} must be an object.`);
  }
  return value;
}

function requireOnlyKeys(record, allowedKeys, scope) {
  const allowed = new Set(allowedKeys);
  const unknown = Object.keys(record).filter((key) => !allowed.has(key));
  if (unknown.length > 0) {
    invalid(`${scope} contains unknown fields: ${unknown.join(", ")}.`);
  }
}

function requireString(record, key, scope) {
  const value = record[key];
  if (typeof value !== "string" || value.length === 0) {
    invalid(`${scope}.${key} must be a non-empty string.`);
  }
  return value;
}

function requireNumber(record, key, scope) {
  const value = record[key];
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    invalid(`${scope}.${key} must be a non-negative finite number.`);
  }
  return value;
}

function requireExact(value, expected, name) {
  if (value !== expected) {
    invalid(`${name} must be ${JSON.stringify(expected)}.`);
  }
}

function requireSha(record, key, scope) {
  const value = requireString(record, key, scope);
  if (!FULL_SHA.test(value)) {
    invalid(`${scope}.${key} must be a full 40-character Git SHA.`);
  }
  return value;
}

function requireDate(record, key, scope) {
  const value = requireString(record, key, scope);
  const parsed = new Date(`${value}T00:00:00Z`);
  if (!ISO_DATE.test(value)
      || Number.isNaN(parsed.valueOf())
      || parsed.toISOString().slice(0, 10) !== value) {
    invalid(`${scope}.${key} must use YYYY-MM-DD.`);
  }
  return value;
}

function requireRef(record, key, scope) {
  const value = requireString(record, key, scope);
  if (!PERSISTENT_REF.test(value)) {
    invalid(`${scope}.${key} must be an explicit refs/heads/* or refs/tags/* coordinate.`);
  }
  return value;
}

function requireMetricRecord(record, fields, scope) {
  for (const field of fields) {
    requireNumber(record, field, scope);
  }
}

export function validateVerificationBaseline(baseline) {
  requireRecord(baseline, "baseline");
  requireOnlyKeys(baseline, [
    "schemaVersion",
    "targetRelease",
    "status",
    "codeGateEvidence",
    "historicalRuntimeEvidence",
    "freshRevalidation",
    "deferred32GiBValidation",
    "pendingRelease",
  ], "baseline");
  if (![2, 3].includes(baseline.schemaVersion)) {
    invalid("schemaVersion must be 2 or 3.");
  }

  const targetRelease = requireString(baseline, "targetRelease", "baseline");
  if (!STABLE_RELEASE.test(targetRelease)) {
    invalid("targetRelease must be a stable vMAJOR.MINOR.PATCH value.");
  }
  const releaseStatus = requireString(baseline, "status", "baseline");
  if (!["release-candidate", "released"].includes(releaseStatus)) {
    invalid("status must be release-candidate or released.");
  }

  const gates = requireRecord(baseline.codeGateEvidence, "codeGateEvidence");
  requireOnlyKeys(gates, [
    "verifiedOn", "objectRef", "objectCommit", "sourcePath", "sourceBlob",
    "backend", "frontend",
  ], "codeGateEvidence");
  const gateVerifiedOn = requireDate(gates, "verifiedOn", "codeGateEvidence");
  const gateRef = requireRef(gates, "objectRef", "codeGateEvidence");
  const gateCommit = requireSha(gates, "objectCommit", "codeGateEvidence");
  requireExact(
    requireString(gates, "sourcePath", "codeGateEvidence"),
    ".github/verification-baseline.json",
    "codeGateEvidence.sourcePath",
  );
  requireSha(gates, "sourceBlob", "codeGateEvidence");
  if (releaseStatus === "released" && gateRef !== `refs/tags/${targetRelease}`) {
    invalid("released codeGateEvidence.objectRef must name targetRelease's tag.");
  }

  const pending = baseline.pendingRelease;
  if (pending !== undefined) {
    requireExact(baseline.schemaVersion, 3, "schemaVersion with pendingRelease");
    const pendingRecord = requireRecord(pending, "pendingRelease");
    requireOnlyKeys(pendingRecord, [
      "targetRelease", "status", "verifiedOn", "objectCommit", "sourcePath",
      "sourceBlob", "runtimeEvidence", "frontend",
    ], "pendingRelease");
    const pendingTarget = requireString(pendingRecord, "targetRelease", "pendingRelease");
    if (!STABLE_RELEASE.test(pendingTarget) || pendingTarget === targetRelease) {
      invalid("pendingRelease.targetRelease must name a different stable release.");
    }
    const pendingStatus = requireString(pendingRecord, "status", "pendingRelease");
    if (!["release-candidate", "released"].includes(pendingStatus)) {
      invalid("pendingRelease.status must be release-candidate or released.");
    }
    const pendingVerifiedOn = requireDate(pendingRecord, "verifiedOn", "pendingRelease");
    if (pendingVerifiedOn < gateVerifiedOn) {
      invalid("pendingRelease must not predate the frozen released baseline.");
    }
    requireSha(pendingRecord, "objectCommit", "pendingRelease");
    requireExact(
      requireString(pendingRecord, "sourcePath", "pendingRelease"),
      "docs/frontend-layout-restructure-plan.md",
      "pendingRelease.sourcePath",
    );
    requireSha(pendingRecord, "sourceBlob", "pendingRelease");
    requireExact(
      requireString(pendingRecord, "runtimeEvidence", "pendingRelease"),
      "UNCHANGED / NOT REVALIDATED",
      "pendingRelease.runtimeEvidence",
    );
    const pendingFrontend = requireRecord(pendingRecord.frontend, "pendingRelease.frontend");
    requireOnlyKeys(pendingFrontend, [
      "unitAndContractTests", "developmentE2E", "productionE2E", "layerRules",
      "lineCoverage", "lineCoverageMinimum",
    ], "pendingRelease.frontend");
    requireMetricRecord(pendingFrontend, [
      "unitAndContractTests", "developmentE2E", "productionE2E", "layerRules",
      "lineCoverage", "lineCoverageMinimum",
    ], "pendingRelease.frontend");
    if (pendingFrontend.lineCoverage < pendingFrontend.lineCoverageMinimum) {
      invalid("pendingRelease frontend coverage cannot be below its recorded minimum.");
    }
  } else if (baseline.schemaVersion === 3) {
    invalid("schemaVersion 3 requires pendingRelease.");
  }

  const backend = requireRecord(gates.backend, "codeGateEvidence.backend");
  requireOnlyKeys(backend, [
    "surefireReports", "tests", "failures", "errors", "skipped", "pmdReports",
    "pmdViolations", "spotbugsReports", "spotbugsPriority1", "spotbugsPriority2",
    "spotbugsPriority3", "lineCoverage", "lineCoverageMinimum",
  ], "codeGateEvidence.backend");
  requireMetricRecord(backend, [
    "surefireReports", "tests", "failures", "errors", "skipped",
    "pmdReports", "pmdViolations", "spotbugsReports", "spotbugsPriority1",
    "spotbugsPriority2", "spotbugsPriority3", "lineCoverage", "lineCoverageMinimum",
  ], "codeGateEvidence.backend");
  const frontend = requireRecord(gates.frontend, "codeGateEvidence.frontend");
  requireOnlyKeys(frontend, [
    "unitAndContractTests", "developmentE2E", "productionE2E", "layerRules",
    "deliveryRules", "deploymentRules", "releaseMaterialRules", "lineCoverage",
    "lineCoverageMinimum",
  ], "codeGateEvidence.frontend");
  requireMetricRecord(frontend, [
    "unitAndContractTests", "developmentE2E", "productionE2E", "layerRules",
    "deliveryRules", "deploymentRules", "releaseMaterialRules", "lineCoverage",
    "lineCoverageMinimum",
  ], "codeGateEvidence.frontend");
  if (backend.lineCoverage < backend.lineCoverageMinimum
      || frontend.lineCoverage < frontend.lineCoverageMinimum) {
    invalid("released code-gate coverage cannot be below its recorded minimum.");
  }
  if (backend.failures !== 0 || backend.errors !== 0 || backend.skipped !== 0
      || backend.pmdViolations !== 0 || backend.spotbugsPriority1 !== 0) {
    invalid("released code-gate evidence contains a non-passing backend counter.");
  }

  const historical = requireRecord(
    baseline.historicalRuntimeEvidence,
    "historicalRuntimeEvidence",
  );
  requireOnlyKeys(historical, [
    "status", "observedFrom", "observedThrough", "hostBoundary", "sourceSnapshots",
    "coreMiddleware", "representativeInstances", "capacityRequests",
    "capacityConcurrency", "browserConsoleErrors", "browserNetworkErrors",
  ], "historicalRuntimeEvidence");
  requireExact(
    requireString(historical, "status", "historicalRuntimeEvidence"),
    "FROZEN HISTORICAL EVIDENCE",
    "historicalRuntimeEvidence.status",
  );
  const observedFrom = requireDate(
    historical,
    "observedFrom",
    "historicalRuntimeEvidence",
  );
  const observedThrough = requireDate(
    historical,
    "observedThrough",
    "historicalRuntimeEvidence",
  );
  if (observedFrom > observedThrough) {
    invalid("historicalRuntimeEvidence observedFrom must not follow observedThrough.");
  }
  requireString(historical, "hostBoundary", "historicalRuntimeEvidence");
  requireMetricRecord(historical, [
    "coreMiddleware", "representativeInstances", "capacityRequests",
    "capacityConcurrency", "browserConsoleErrors", "browserNetworkErrors",
  ], "historicalRuntimeEvidence");
  if (!Array.isArray(historical.sourceSnapshots) || historical.sourceSnapshots.length === 0) {
    invalid("historicalRuntimeEvidence.sourceSnapshots must be a non-empty array.");
  }
  for (const [index, snapshotValue] of historical.sourceSnapshots.entries()) {
    const scope = `historicalRuntimeEvidence.sourceSnapshots[${index}]`;
    const snapshot = requireRecord(snapshotValue, scope);
    requireOnlyKeys(snapshot, [
      "path", "objectRef", "objectCommit", "sourceCommit", "sourceBlob",
    ], scope);
    const sourcePath = requireString(snapshot, "path", scope);
    if (!sourcePath.startsWith("docs/") || !sourcePath.endsWith(".md")) {
      invalid(`${scope}.path must identify a Markdown file under docs/.`);
    }
    requireRef(snapshot, "objectRef", scope);
    requireSha(snapshot, "objectCommit", scope);
    requireSha(snapshot, "sourceCommit", scope);
    requireSha(snapshot, "sourceBlob", scope);
  }

  const fresh = requireRecord(baseline.freshRevalidation, "freshRevalidation");
  requireOnlyKeys(fresh, [
    "observedOn", "objectCommit", "fixedByCommit", "objectBoundary", "evidenceClass",
    "runtimeAdjustment", "status", "coreMiddlewareStarted", "businessJvmPortsObserved",
    "businessJvmPortsExpected", "minimumAvailablePhysicalMemoryGiB", "coreSmokeVerdict",
    "capacityValidation", "representativeThreeInstanceValidation",
    "failureRecoveryValidation", "browserBusinessRevalidation", "sourcePath",
    "sourceCommit", "sourceBlob",
  ], "freshRevalidation");
  const freshObservedOn = requireDate(fresh, "observedOn", "freshRevalidation");
  requireSha(fresh, "objectCommit", "freshRevalidation");
  requireSha(fresh, "fixedByCommit", "freshRevalidation");
  requireString(fresh, "objectBoundary", "freshRevalidation");
  requireExact(
    requireString(fresh, "evidenceClass", "freshRevalidation"),
    "SESSION READBACK SUMMARY / RAW OUTPUT NOT PERSISTED",
    "freshRevalidation.evidenceClass",
  );
  requireString(fresh, "runtimeAdjustment", "freshRevalidation");
  requireExact(
    requireString(fresh, "sourcePath", "freshRevalidation"),
    "docs/32gib-extended-validation-runbook.md",
    "freshRevalidation.sourcePath",
  );
  requireSha(fresh, "sourceCommit", "freshRevalidation");
  requireSha(fresh, "sourceBlob", "freshRevalidation");
  requireMetricRecord(fresh, [
    "coreMiddlewareStarted", "businessJvmPortsObserved", "businessJvmPortsExpected",
    "minimumAvailablePhysicalMemoryGiB",
  ], "freshRevalidation");
  if (fresh.businessJvmPortsExpected === 0
      || fresh.businessJvmPortsObserved > fresh.businessJvmPortsExpected) {
    invalid("freshRevalidation business JVM port counts are inconsistent.");
  }
  requireExact(
    requireString(fresh, "status", "freshRevalidation"),
    "INCONCLUSIVE / HOST CAPACITY BOUNDARY",
    "freshRevalidation.status",
  );
  requireExact(
    requireString(fresh, "coreSmokeVerdict", "freshRevalidation"),
    "NOT COMPLETED",
    "freshRevalidation.coreSmokeVerdict",
  );
  for (const field of [
    "capacityValidation",
    "representativeThreeInstanceValidation",
    "failureRecoveryValidation",
    "browserBusinessRevalidation",
  ]) {
    requireExact(
      requireString(fresh, field, "freshRevalidation"),
      "NOT RUN",
      `freshRevalidation.${field}`,
    );
  }
  if (observedThrough > freshObservedOn) {
    invalid("historicalRuntimeEvidence must end on or before freshRevalidation.");
  }
  if (freshObservedOn > gateVerifiedOn) {
    invalid("freshRevalidation must not follow the recorded release code-gate verification.");
  }

  const deferred = requireRecord(
    baseline.deferred32GiBValidation,
    "deferred32GiBValidation",
  );
  requireOnlyKeys(deferred, ["executed"], "deferred32GiBValidation");
  requireExact(deferred.executed, false, "deferred32GiBValidation.executed");

  return baseline;
}

export function renderVerificationSummary(baseline) {
  validateVerificationBaseline(baseline);
  const gates = baseline.codeGateEvidence;
  const backend = gates.backend;
  const frontend = gates.frontend;
  const historical = baseline.historicalRuntimeEvidence;
  const fresh = baseline.freshRevalidation;
  const deferred = baseline.deferred32GiBValidation;
  const pending = baseline.pendingRelease;
  const pendingSummary = pending ? `
## \`${pending.targetRelease}\` 候选代码门禁

| 项目 | 当前值 |
| --- | --- |
| 候选状态 | \`${pending.status}\` |
| 已验证代码对象 | \`${pending.objectCommit}\` |
| 候选数字来源 | \`${pending.objectCommit}:${pending.sourcePath}\`；blob \`${pending.sourceBlob}\` |
| 验证日期 | ${pending.verifiedOn} |
| 前端单元/契约 | ${pending.frontend.unitAndContractTests} / ${pending.frontend.unitAndContractTests} |
| 前端聚合行覆盖率 | ${pending.frontend.lineCoverage}%（门禁 ≥ ${pending.frontend.lineCoverageMinimum}%） |
| 开发态 Playwright | ${pending.frontend.developmentE2E} / ${pending.frontend.developmentE2E} |
| 生产构建 Playwright | ${pending.frontend.productionE2E} / ${pending.frontend.productionE2E} |
| 前端分层规则 | ${pending.frontend.layerRules} 条 |
| 真实运行证据 | \`${pending.runtimeEvidence}\` |

该候选只新增前端代码与视觉门禁事实；后端、真实中间件、容量、三实例和故障恢复仍沿用
下方已经冻结的历史边界，不表述为本轮重新执行。
` : "";
  const historicalSources = historical.sourceSnapshots
    .map((source) => (
      `- [${path.basename(source.path, ".md")}](${source.path.replace(/^docs\//u, "")})`
      + `：运行对象 \`${source.objectRef}\` → \`${source.objectCommit}\`；`
      + `证据坐标 \`${source.sourceCommit}:${source.path}\`；blob \`${source.sourceBlob}\``
    ))
    .join("\n");
  return `# 当前验证摘要

> 本文件由 \`.github/verification-baseline.json\` 通过
> \`node tools/render-verification-summary.mjs\` 生成。逐批过程由 Git 历史追溯，
> 本页刻意分开发布对象代码门禁、历史运行证据、本轮 fresh 复验和未来 32 GiB 协议；
> 它们不是同一时间、同一对象或同一宿主条件下的一次“完整验证”。

## 发布坐标

| 项目 | 当前值 |
| --- | --- |
| 目标版本 | \`${baseline.targetRelease}\` |
| 状态 | \`${baseline.status}\` |
| 门禁对象 | \`${gates.objectRef}\` |
| 门禁对象提交 | \`${gates.objectCommit}\` |
| 门禁数字来源 | \`${gates.objectCommit}:${gates.sourcePath}\`；blob \`${gates.sourceBlob}\` |
| 代码门禁验证日期 | ${gates.verifiedOn} |
${pending ? `| 下一候选 | \`${pending.targetRelease}\`（\`${pending.status}\`） |` : ""}

## \`${baseline.targetRelease}\` 发布对象代码门禁

| 范围 | 结果 |
| --- | --- |
| 后端 Maven | ${backend.surefireReports} 份 Surefire 报告，${backend.tests} tests，${backend.failures} failures，${backend.errors} errors，${backend.skipped} skipped |
| 后端行覆盖率 | ${backend.lineCoverage}%（门禁 ≥ ${backend.lineCoverageMinimum}%） |
| PMD | ${backend.pmdReports} 份报告，${backend.pmdViolations} 违规 |
| SpotBugs | ${backend.spotbugsReports} 份报告，P1=${backend.spotbugsPriority1}，P2=${backend.spotbugsPriority2}，P3=${backend.spotbugsPriority3}；分类边界见 [SpotBugs 台账](quality/spotbugs-triage.md) |
| 前端单元/契约 | ${frontend.unitAndContractTests} / ${frontend.unitAndContractTests} |
| 前端聚合行覆盖率 | ${frontend.lineCoverage}%（门禁 ≥ ${frontend.lineCoverageMinimum}%） |
| 开发态 Playwright | ${frontend.developmentE2E} / ${frontend.developmentE2E} |
| 生产构建 Playwright | ${frontend.productionE2E} / ${frontend.productionE2E} |
| 前端静态门禁 | ${frontend.layerRules} 条分层规则，${frontend.deliveryRules} 条交付规则，${frontend.deploymentRules} 条部署规则，${frontend.releaseMaterialRules} 条发布材料规则 |

GitHub Actions 运行后，外部访问者可在仓库 Actions 页面复核同一套后端、前端、
架构、文档和安全门禁。

${pendingSummary}

## 历史冻结运行证据

状态：\`${historical.status}\`。

以下事实来自 ${historical.observedFrom} 至 ${historical.observedThrough} 的冻结快照，宿主边界为
\`${historical.hostBoundary}\`。它们继续证明对应历史对象与分组/串行拓扑，不表示
${gates.verifiedOn} 的 fresh 全拓扑重新通过：

- ${historical.coreMiddleware} 个核心中间件；
- 代表服务 ${historical.representativeInstances} 实例竞争、滚动升级和故障恢复；
- ${historical.capacityRequests} 请求 / ${historical.capacityConcurrency} 并发容量基线；
- MySQL 所有者事实、Redis 降级、RocketMQ Outbox/消费幂等、Nacos 路由、MinIO、
  ClamAV、OpenSearch、观测追踪与补偿对账；
- 真实 Chromium 和 F12/CDP 验证，记录中的页面控制台错误
  ${historical.browserConsoleErrors}、网络错误 ${historical.browserNetworkErrors}。

冻结来源：

${historicalSources}

## ${fresh.observedOn} fresh 复验状态

| 项目 | 直接观察 / 裁决 |
| --- | --- |
| 对象提交 | \`${fresh.objectCommit}\` |
| 修复合并提交 | \`${fresh.fixedByCommit}\` |
| 对象边界 | ${fresh.objectBoundary} |
| 证据等级 | \`${fresh.evidenceClass}\` |
| 运行调整 | ${fresh.runtimeAdjustment} |
| 中间件与 JVM | ${fresh.coreMiddlewareStarted} 个核心中间件；${fresh.businessJvmPortsObserved}/${fresh.businessJvmPortsExpected} 个业务端口被观察到 |
| 最低可用物理内存 | 约 ${fresh.minimumAvailablePhysicalMemoryGiB} GiB |
| 裁决 | \`${fresh.status}\` |
| Core Smoke | \`${fresh.coreSmokeVerdict}\` |
| 容量复验 | \`${fresh.capacityValidation}\` |
| 代表服务三实例 | \`${fresh.representativeThreeInstanceValidation}\` |
| 故障恢复 | \`${fresh.failureRecoveryValidation}\` |
| 真实浏览器业务复验 | \`${fresh.browserBusinessRevalidation}\` |

本轮在完整 JVM 拓扑启动后越过宿主资源停止线；表中各项保持其实际状态，没有被汇总成
PASS。停止现场与适用限制见
[32 GiB 扩展验收协议](${fresh.sourcePath.replace(/^docs\//u, "")})，固定证据坐标为
\`${fresh.sourceCommit}:${fresh.sourcePath}\`（blob \`${fresh.sourceBlob}\`）。

## 32 GiB 延期协议

状态：\`PLANNED / DEFERRED\`；已执行：\`${deferred.executed}\`。

本记录的 \`executed\` 为 \`${deferred.executed}\`；协议只冻结执行顺序和停止线，尚未产生
32 GiB 容量、三实例或故障恢复事实。入口见
[32 GiB 扩展验收协议](${fresh.sourcePath.replace(/^docs\//u, "")})，固定证据坐标为
\`${fresh.sourceCommit}:${fresh.sourcePath}\`（blob \`${fresh.sourceBlob}\`）。
未来 32 GiB 执行结果必须新增独立证据线；不得把本条历史记录的 \`executed: false\`
回写为 true。

证据入口与适用边界见 [验证索引](verification-index.md)。H2、Mock API、浏览器夹具和
真实中间件脚本分别证明不同层级，不能互相替代。

## 常用命令

\`\`\`bash
# 仓库与架构
node tools/run-repository-tests.mjs
node tools/check-backend-boundaries.mjs
node tools/check-markdown-links.mjs
node tools/render-verification-summary.mjs --check

# 后端
cd backend
./mvnw clean verify
./mvnw -DskipTests install org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check

# 前端
cd ../frontend
corepack enable
pnpm install --frozen-lockfile
pnpm check
\`\`\`
`;
}

const invokedPath = process.argv[1] ? path.resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const baselinePath = path.join(repositoryRoot, ".github", "verification-baseline.json");
  const outputPath = path.join(repositoryRoot, "docs", "verification-summary.md");
  const baseline = JSON.parse(await fs.readFile(baselinePath, "utf8"));
  const rendered = renderVerificationSummary(baseline);

  if (process.argv.includes("--check")) {
    let current = "";
    try {
      current = await fs.readFile(outputPath, "utf8");
    } catch {
      // Missing generated output is reported as drift below.
    }
    if (current !== rendered) {
      console.error(
        "docs/verification-summary.md is stale. Run "
        + "`node tools/render-verification-summary.mjs`.",
      );
      process.exitCode = 1;
    } else {
      console.log("Verification summary is current.");
    }
  } else {
    await fs.writeFile(outputPath, rendered, "utf8");
    console.log(`Wrote ${path.relative(repositoryRoot, outputPath)}.`);
  }
}
