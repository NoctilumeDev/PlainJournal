# 当前验证摘要

> 本文件由 `.github/verification-baseline.json` 通过
> `node tools/render-verification-summary.mjs` 生成。逐批过程由 Git 历史追溯，
> 当前入口只引用本摘要和精选终局验收快照。

## 发布坐标

| 项目 | 当前值 |
| --- | --- |
| 目标版本 | `v1.0.8` |
| 状态 | `released` |
| 最近完整验证日期 | 2026-08-21 |

## 可重复代码门禁

| 范围 | 结果 |
| --- | --- |
| 后端 Maven | 100 份 Surefire 报告，436 tests，0 failures，0 errors，0 skipped |
| 后端行覆盖率 | 72.4%（门禁 ≥ 70%） |
| PMD | 12 份报告，0 违规 |
| SpotBugs | 12 份报告，P1=0，P2=247，P3=66；分类边界见 [SpotBugs 台账](quality/spotbugs-triage.md) |
| 前端单元/契约 | 323 / 323 |
| 前端聚合行覆盖率 | 70.16%（门禁 ≥ 70%） |
| 开发态 Playwright | 60 / 60 |
| 生产构建 Playwright | 3 / 3 |
| 前端静态门禁 | 28 条分层规则，3 条交付规则，3 条部署规则，3 条发布材料规则 |

GitHub Actions 运行后，外部访问者可在仓库 Actions 页面复核同一套后端、前端、
架构、文档和安全门禁。

## 真实机制证据

真实基础设施验证不在 GitHub 托管 Runner 中冒充执行。当前本机证据覆盖：

- 7 个核心中间件；
- 代表服务 3 实例竞争、滚动升级和故障恢复；
- 1000 请求 / 100 并发容量基线；
- MySQL 所有者事实、Redis 降级、RocketMQ Outbox/消费幂等、Nacos 路由、MinIO、
  ClamAV、OpenSearch、观测追踪与补偿对账；
- 真实 Chromium 和 F12/CDP 验证，记录中的页面控制台错误
  0、网络错误 0。

证据入口与适用边界见 [验证索引](verification-index.md)。H2、Mock API、浏览器夹具和
真实中间件脚本分别证明不同层级，不能互相替代。

## 常用命令

```bash
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
```
