# 当前验证摘要

> 本文件由 `.github/verification-baseline.json` 通过
> `node tools/render-verification-summary.mjs` 生成。逐批过程由 Git 历史追溯，
> 本页刻意分开发布对象代码门禁、历史运行证据、本轮 fresh 复验和未来 32 GiB 协议；
> 它们不是同一时间、同一对象或同一宿主条件下的一次“完整验证”。

## 发布坐标

| 项目 | 当前值 |
| --- | --- |
| 目标版本 | `v1.0.10` |
| 状态 | `released` |
| 门禁对象 | `refs/tags/v1.0.10` |
| 门禁对象提交 | `52f7de692d26e760661c4d3172746f1ac517952c` |
| 门禁数字来源 | `52f7de692d26e760661c4d3172746f1ac517952c:.github/verification-baseline.json`；blob `4ac8d553009c432be454e00baf22263312fcebae` |
| 代码门禁验证日期 | 2026-08-28 |

## `v1.0.10` 发布对象代码门禁

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

## 历史冻结运行证据

状态：`FROZEN HISTORICAL EVIDENCE`。

以下事实来自 2026-07-28 至 2026-08-04 的冻结快照，宿主边界为
`16 GiB Windows；分组与串行拓扑`。它们继续证明对应历史对象与分组/串行拓扑，不表示
2026-08-28 的 fresh 全拓扑重新通过：

- 7 个核心中间件；
- 代表服务 3 实例竞争、滚动升级和故障恢复；
- 1000 请求 / 100 并发容量基线；
- MySQL 所有者事实、Redis 降级、RocketMQ Outbox/消费幂等、Nacos 路由、MinIO、
  ClamAV、OpenSearch、观测追踪与补偿对账；
- 真实 Chromium 和 F12/CDP 验证，记录中的页面控制台错误
  0、网络错误 0。

冻结来源：

- [m0-m8-three-layer-acceptance-20260728](evidence/m0-m8-three-layer-acceptance-20260728.md)：运行对象 `refs/tags/v1.0.0` → `d563507f16f50602e997d2272a400fa54606ff93`；证据坐标 `7b54cff681363555c0318621d9b8a6ad8d7edb47:docs/evidence/m0-m8-three-layer-acceptance-20260728.md`；blob `612aa5b639ff37f2fd8f0018aa2c7e892c522120`
- [v1.0.2-engineering-acceptance-20260804](evidence/v1.0.2-engineering-acceptance-20260804.md)：运行对象 `refs/tags/v1.0.2` → `da5ae5597e35089ec891e34cf49cc82f5c136298`；证据坐标 `7b54cff681363555c0318621d9b8a6ad8d7edb47:docs/evidence/v1.0.2-engineering-acceptance-20260804.md`；blob `72b820bf2acab356c6b2b69b14a950958bafec48`

## 2026-08-28 fresh 复验状态

| 项目 | 直接观察 / 裁决 |
| --- | --- |
| 对象提交 | `a4fecf46e918278e625c1815b16ff6fe2656ffc7` |
| 修复合并提交 | `1453aaaf6746700c1d75e4f9d26f28f95bc4d599` |
| 对象边界 | v1.0.10 修复前的公开提交；公开 Nacos 模板缺陷仍存在 |
| 证据等级 | `SESSION READBACK SUMMARY / RAW OUTPUT NOT PERSISTED` |
| 运行调整 | 仅在 ignored .env 中替换 Nacos token，以继续定位宿主容量 |
| 中间件与 JVM | 7 个核心中间件；8/8 个业务端口被观察到 |
| 最低可用物理内存 | 约 0.46 GiB |
| 裁决 | `INCONCLUSIVE / HOST CAPACITY BOUNDARY` |
| Core Smoke | `NOT COMPLETED` |
| 容量复验 | `NOT RUN` |
| 代表服务三实例 | `NOT RUN` |
| 故障恢复 | `NOT RUN` |
| 真实浏览器业务复验 | `NOT RUN` |

本轮在完整 JVM 拓扑启动后越过宿主资源停止线；表中各项保持其实际状态，没有被汇总成
PASS。停止现场与适用限制见
[32 GiB 扩展验收协议](32gib-extended-validation-runbook.md)，固定证据坐标为
`a42f21fec26e6973f34115e2376428a0beef9c5d:docs/32gib-extended-validation-runbook.md`（blob `9d987675eb8a94601e3631e712ea01e6c774ba4e`）。

## 32 GiB 延期协议

状态：`PLANNED / DEFERRED`；已执行：`false`。

本记录的 `executed` 为 `false`；协议只冻结执行顺序和停止线，尚未产生
32 GiB 容量、三实例或故障恢复事实。入口见
[32 GiB 扩展验收协议](32gib-extended-validation-runbook.md)，固定证据坐标为
`a42f21fec26e6973f34115e2376428a0beef9c5d:docs/32gib-extended-validation-runbook.md`（blob `9d987675eb8a94601e3631e712ea01e6c774ba4e`）。
未来 32 GiB 执行结果必须新增独立证据线；不得把本条历史记录的 `executed: false`
回写为 true。

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
