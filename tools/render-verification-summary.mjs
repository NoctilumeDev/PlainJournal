import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function renderVerificationSummary(baseline) {
  const backend = baseline.backend;
  const frontend = baseline.frontend;
  const real = baseline.realEvidence;
  return `# 当前验证摘要

> 本文件由 \`.github/verification-baseline.json\` 通过
> \`node tools/render-verification-summary.mjs\` 生成。历史阶段报告保留当时数字，
> 当前入口只引用本摘要。

## 发布坐标

| 项目 | 当前值 |
| --- | --- |
| 目标版本 | \`${baseline.targetRelease}\` |
| 状态 | \`${baseline.status}\` |
| 最近完整验证日期 | ${baseline.verifiedOn} |

## 可重复代码门禁

| 范围 | 结果 |
| --- | --- |
| 后端 Maven | ${backend.surefireReports} 份 Surefire 报告，${backend.tests} tests，${backend.failures} failures，${backend.errors} errors，${backend.skipped} skipped |
| PMD | ${backend.pmdReports} 份报告，${backend.pmdViolations} 违规 |
| SpotBugs | ${backend.spotbugsReports} 份报告，P1=${backend.spotbugsPriority1}，P2=${backend.spotbugsPriority2}，P3=${backend.spotbugsPriority3}；分类边界见 [SpotBugs 台账](quality/spotbugs-triage.md) |
| 前端单元/契约 | ${frontend.unitAndContractTests} / ${frontend.unitAndContractTests} |
| 开发态 Playwright | ${frontend.developmentE2E} / ${frontend.developmentE2E} |
| 生产构建 Playwright | ${frontend.productionE2E} / ${frontend.productionE2E} |
| 前端静态门禁 | ${frontend.layerRules} 条分层规则，${frontend.deliveryRules} 条交付规则，${frontend.deploymentRules} 条部署规则，${frontend.releaseMaterialRules} 条发布材料规则 |

GitHub Actions 运行后，外部访问者可在仓库 Actions 页面复核同一套后端、前端、
架构、文档和安全门禁。

## 真实机制证据

真实基础设施验证不在 GitHub 托管 Runner 中冒充执行。当前本机证据覆盖：

- ${real.coreMiddleware} 个核心中间件；
- 代表服务 ${real.representativeInstances} 实例竞争、滚动升级和故障恢复；
- ${real.capacityRequests} 请求 / ${real.capacityConcurrency} 并发容量基线；
- MySQL 所有者事实、Redis 降级、RocketMQ Outbox/消费幂等、Nacos 路由、MinIO、
  ClamAV、OpenSearch、观测追踪与补偿对账；
- 真实 Chromium 和 F12/CDP 验证，记录中的页面控制台错误
  ${real.browserConsoleErrors}、网络错误 ${real.browserNetworkErrors}。

证据入口与适用边界见 [验证索引](verification-index.md)。H2、Mock API、浏览器夹具和
真实中间件脚本分别证明不同层级，不能互相替代。

## 常用命令

\`\`\`bash
# 仓库与架构
node --test tools/*.test.mjs
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
