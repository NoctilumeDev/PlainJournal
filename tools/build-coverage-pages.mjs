import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  backendCoverageModules,
  coveragePercentage,
  parseJacocoCsv,
} from "./check-backend-coverage.mjs";
import {
  coveragePackages,
  summarizeCoverage,
} from "../frontend/tools/check-coverage.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const outputRoot = path.join(repositoryRoot, ".pages", "coverage");
const backendOutput = path.join(outputRoot, "backend");
const frontendOutput = path.join(outputRoot, "frontend");

await fs.rm(outputRoot, { recursive: true, force: true });
await fs.mkdir(backendOutput, { recursive: true });
await fs.mkdir(frontendOutput, { recursive: true });

const backendEntries = [];
for (const name of backendCoverageModules) {
  const reportRoot = path.join(
    repositoryRoot,
    "backend",
    name,
    "target",
    "site",
    "jacoco",
  );
  const counters = parseJacocoCsv(
    await fs.readFile(path.join(reportRoot, "jacoco.csv"), "utf8"),
  );
  const slug = name.replaceAll("/", "-");
  await fs.cp(reportRoot, path.join(backendOutput, slug), { recursive: true });
  backendEntries.push({
    name,
    slug,
    ...counters,
    percentage: coveragePercentage(counters),
  });
}

const frontendEntries = [];
for (const definition of coveragePackages) {
  const reportRoot = path.join(
    repositoryRoot,
    "frontend",
    "coverage",
    definition.name,
  );
  const summary = JSON.parse(
    await fs.readFile(path.join(reportRoot, "coverage-summary.json"), "utf8"),
  );
  const lines = summary.total.lines;
  await fs.cp(
    reportRoot,
    path.join(frontendOutput, definition.name),
    { recursive: true },
  );
  frontendEntries.push({
    name: definition.name,
    covered: lines.covered,
    total: lines.total,
    percentage: lines.pct,
  });
}

const backendAggregate = backendEntries.reduce(
  (summary, entry) => ({
    covered: summary.covered + entry.covered,
    total: summary.total + entry.covered + entry.missed,
  }),
  { covered: 0, total: 0 },
);
backendAggregate.percentage = Number(
  ((backendAggregate.covered / backendAggregate.total) * 100).toFixed(2),
);
const frontendAggregate = summarizeCoverage(frontendEntries);

const row = (scope, percentage, covered, total, href) => `
        <tr>
          <td><a href="${href}">${scope}</a></td>
          <td>${percentage}%</td>
          <td>${covered} / ${total}</td>
        </tr>`;

const html = `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>PlainJournal 覆盖率报告</title>
    <style>
      :root { color-scheme: light; font-family: Inter, "Noto Sans SC", system-ui, sans-serif; color: #1c2624; background: #f3f5f1; }
      body { width: min(960px, calc(100% - 32px)); margin: 0 auto; padding: 48px 0 72px; }
      a { color: #315f50; }
      h1, h2 { font-family: "Noto Serif SC", Georgia, serif; font-weight: 600; letter-spacing: 0; }
      p { line-height: 1.75; }
      table { width: 100%; border-collapse: collapse; margin: 18px 0 36px; background: #fff; }
      th, td { padding: 12px 14px; border: 1px solid #cbd4ce; text-align: left; }
      th { background: #e7ebe6; }
      .summary { display: flex; flex-wrap: wrap; gap: 12px 28px; padding: 18px; border-left: 3px solid #789b8d; background: #fff; }
    </style>
  </head>
  <body>
    <p><a href="../">← 返回在线预览</a></p>
    <h1>PlainJournal 覆盖率报告</h1>
    <p>
      报告由 GitHub Pages 工作流从完整单元/集成测试生成。后端统计项目生产类，
      前端统计四个 workspace 的全部可执行源码；声明文件、测试文件和启动入口不计入。
    </p>
    <div class="summary">
      <strong>后端 ${backendAggregate.percentage}%</strong>
      <span>${backendAggregate.covered} / ${backendAggregate.total} 行</span>
      <strong>前端 ${frontendAggregate.percentage}%</strong>
      <span>${frontendAggregate.covered} / ${frontendAggregate.total} 行</span>
    </div>
    <h2>后端模块</h2>
    <table>
      <thead><tr><th>模块</th><th>行覆盖率</th><th>覆盖行</th></tr></thead>
      <tbody>
${backendEntries.map((entry) => row(
  entry.name,
  entry.percentage,
  entry.covered,
  entry.covered + entry.missed,
  `backend/${entry.slug}/`,
)).join("\n")}
      </tbody>
    </table>
    <h2>前端 Workspace</h2>
    <table>
      <thead><tr><th>范围</th><th>行覆盖率</th><th>覆盖行</th></tr></thead>
      <tbody>
${frontendEntries.map((entry) => row(
  entry.name,
  entry.percentage,
  entry.covered,
  entry.total,
  `frontend/${entry.name}/`,
)).join("\n")}
      </tbody>
    </table>
  </body>
</html>
`;

await fs.writeFile(path.join(outputRoot, "index.html"), html, "utf8");
console.log(
  `Coverage pages written to ${path.relative(repositoryRoot, outputRoot)}.`,
);
