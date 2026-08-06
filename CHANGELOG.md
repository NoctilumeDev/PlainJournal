# 变更记录

本文件记录《素简记》可交付版本的主要变化。阶段实验的完整时间线和验证证据仍保存在
`docs/`，这里不重复罗列每一条内部实现记录。

## [Unreleased]

- 结果未知的物流轨迹在刷新后只持久化履约编号、外部事件 ID、创建时间和权威读取标记；
  轨迹说明、地点与坐标均不进入 `localStorage`，同一页面会话内仍可使用内存快照原样
  重试，刷新后只能读取 Fulfillment 权威事实确认结果。

## [1.0.3] - 2026-08-06

> 修正正式版本语义、发布门禁和并发评价幂等边界，不增加业务域。

### Changed

- 后端 Reactor 与前端 workspace 统一为正式 `1.0.3`，后端可执行 JAR 改为不含版本号
  的稳定文件名，真实链路脚本和 Dockerfile 不再与具体版本耦合；
- CodeQL Action 更新到当前 Dependabot 提议的完整 Commit SHA；
- README 不再手工复制测试数字，精确版本和验证结果只由验证摘要承载。
- `docs/` 根目录只保留现行产品、架构、运行和验证规范；删除 `26-106` 的逐批施工
  日志，由 Git 历史负责追溯，只保留两份终局验收快照；
- 998 行旧总计划重写为精简当前计划，不再在主分支重复保存历史副本；
- 本地网络文档移除仓库外私有脚本和机器绝对路径，只保留可移植的 Windows 动态端口、
  代理、Docker/VPN、压测和恢复边界。
- Docker 示例数据目录改为仓库内已忽略的 `deploy/docker/.data/`，本机 `.env` 仍可
  覆盖到独立磁盘，不再要求使用固定 `D:` 盘。

### Fixed

- 修复正式 Release 标签内后端仍使用 `-SNAPSHOT` 的版本语义漂移，并增加跨 Maven、
  pnpm、CHANGELOG、Release Notes 和验证基线的一致性门禁；
- 修复 CI 在干净 checkout 上执行空 `git diff --check`，现在按 PR、分支 push 和标签/
  手动事件检查真实变更集；
- 修复 Release shell glob 会接受带额外尾巴的非标准标签，正式标签只允许
  `vMAJOR.MINOR.PATCH`；
- 修复同一评价幂等键并发重试偶发被误判为 `REVIEW_ALREADY_SUBMITTED`：资格锁后按
  资格执行评价当前读，同键返回原结果，不同键保持 409；
- 修复前端命令与幂等键在 Web Crypto 不可用时降级使用 `Math.random()` 的可预测性问题；
- 修复结果未知的物流轨迹将经纬度写入 `localStorage`：同会话仍可原样重试，刷新后本地
  副本会删除坐标，只能按外部事件 ID 读取 Fulfillment 权威事实确认；
- 升级存在安全公告的 `brace-expansion` 与 `undici` 传递依赖；仓库显式固定 npm
  官方源并保留严格 peer 依赖校验，让 Security 工作流审计完整锁文件、阻断中危及以上
  漏洞；
- 修复 Docker 冷启动时 `bootstrap-resources.ps1` 在 Nacos readiness 之前尝试登录，
  并把服务未就绪误报为管理员密码不一致的问题；
- 修复前端 README 仍将已发布版本描述为验收候选的问题。
- 修复前端发布材料检查把验证日期硬编码为 CHANGELOG 发布日期的问题；现在按目标版本
  读取并校验独立的 ISO 发布日期。

### Governance

- 启用 Dependency Graph、Dependabot Security Updates 和 Private Vulnerability
  Reporting，并补充 Dependabot 使用的 `dependencies` 标签；
- 关闭被冻结范围内不计划合并的普通平台升级 PR，Actions 安全更新直接进入本补丁。
- 用仓库测试约束所有关闭 CSRF 的服务均为无状态 Bearer JWT Resource Server；文档明确
  迁移到 HttpOnly Cookie 时必须同步重新设计 CSRF、SameSite 与 CORS。
- 新增文档结构门禁，阻止阶段日志重新进入主分支、验收证据超出白名单、当前文档复制
  可漂移测试数字、机器绝对路径和重复文档；
- 明确 PlainJournal 是 16GB Windows 单机约束下完整的 M0-M8 参考基线；多商户、
  平台账本、结算和 Java/Go 异构协作进入独立 PlainJournalPro。

## [1.0.2] - 2026-08-04

> 不增加业务域、不重做视觉的工程验收与治理收口版本。

### Added

- 后端 JaCoCo 覆盖率门禁：每个可执行模块行覆盖率不低于 65%，聚合不低于 70%；
- 前端 Foundation、UI、Admin、Storefront 四包 V8 覆盖率报告与 70% 聚合门禁；
- GitHub Pages 前后端公开覆盖率入口、结构化 Issue Form 和人工 Release Notes；
- Action 完整 Commit SHA 固定门禁，以及标签发布前的 CI、Security 和 `main`
  祖先校验。

### Changed

- 后端 Reactor 统一为 `1.0.2-SNAPSHOT`，前端 workspace 包统一为 `1.0.2`；
- Dependabot 忽略常规 Major 升级，将 Maven、npm 和 Actions 的 Minor/Patch
  按生态分组；
- 当前验证基线更新为后端 436 tests、前端 319 个单元/契约测试，并公开记录前后端
  聚合行覆盖率。

### Fixed

- 修复结算页在下单结果未知后过早恢复“安全重试”按钮，导致外层请求 Promise 尚未
  释放时点击被并发合并逻辑吞掉的问题；
- 修复三实例 Outbox 验证在设置 `JAVA_TOOL_OPTIONS` 后误把提示行当作 Java
  版本号的问题；
- 修复覆盖率 Artifact 缺失可能掩盖前置失败，以及 Release 对具体 Security Check
  名称的脆弱依赖。

### Verified

- 后端 100 份 Surefire 报告、436 tests、聚合行覆盖率 72.43%，PMD 0 违规，
  SpotBugs P1=0；
- 前端 319 个单元/契约测试、聚合行覆盖率 70.06%、60 个开发态 E2E 和
  3 个生产构建 E2E；
- 真实七中间件 Core Smoke、Trade 1/2/3 实例各 1000 条 Outbox 事件，以及顾客/客服
  双浏览器 WebSocket 链路通过；F12/CDP 的页面、控制台、HTTP 和网络错误均为 0。

## [1.0.1] - 2026-08-03

> 不扩展业务域的开源工程加固版本。

### Added

- GitHub Actions：后端/前端 CI、CodeQL、Dependency Review、pnpm audit、Pages
  在线预览和标签 Release 自动打包；
- Dependabot、贡献指南、行为准则、缺陷/可复现性 Issue 模板和 Pull Request 模板；
- Maven Wrapper、后端架构边界门禁、Markdown 链接门禁和机器可读验证基线；
- UI Demo / Core Smoke / Full Lab 三档验证入口，以及文档、历史、SpotBugs 分类台账
  和机器可读扫描摘要；
- Release 自定义 ZIP、SHA-256、manifest 和 SPDX SBOM 产物。

### Changed

- 后端版本统一为 `1.0.1-SNAPSHOT`，前端 workspace 包统一为 `1.0.1`；
- 基础 UI Demo、开发态 E2E 和生产构建 E2E 从 Windows 专用 PowerShell 改为跨平台
  Node 编排，复杂 Docker 和真实故障脚本继续使用 PowerShell 7；
- 根 README 从阶段证据墙收敛为项目定位、成品、架构、运行和验证入口；
- 当前测试数字由 `.github/verification-baseline.json` 生成，历史报告保留原始快照。

### Fixed

- 修复后端 README 仍宣称 M9 准入、机器绝对路径和 435 tests 旧数字；
- 修复版本矩阵仍展示 141 Vitest / 14 E2E 的状态漂移；
- 修复 Playwright 配置强制指向 Windows Chrome，Linux/macOS 现在可回退到已安装
  Chromium；
- 修复 Windows 演示编排只追踪短命令壳 PID、可能误报停止并遗留 Vite 子进程的问题。
- 修复独立 PMD 在干净 CI 环境无法解析未安装 Reactor 模块的问题，门禁现在先安装
  当前 Reactor 再执行 PMD；
- 修复在线预览在窄屏下按钮文字可能溢出、触控目标过紧的问题。
- 将传递依赖 PostCSS 固定到 `8.5.23`，关闭官方 npm audit 报告的中危
  `sourceMappingURL` 文件读取问题。
- 修复管理员商品目录 E2E 在 Linux Runner 上未等待登录会话写入便继续断言、
  偶发停留在登录页的问题。
- 补充在线预览 favicon，消除浏览器开发者工具中的根路径图标 `404`。

## [1.0.0] - 2026-08-03

> 首个公开开源版本；项目采用 Apache License 2.0。

### Added

- 完整自营 B2C 正向交易、整单售后退款、聊天、通知、GEO、评价、搜索和运营统计；
- Outbox、RocketMQ 幂等消费、补偿、对账、同步韧性、追踪、可观测性和多实例治理；
- Vue 3 + TypeScript 顾客端与管理端，以及“青荷 / 素白”双主题设计系统；
- 固定公开演示夹具、响应式 AVIF/WebP 商品图片和可重复发布截图；
- 顾客端与管理端双 Nginx 镜像、OCI 元数据、History fallback、缓存和同源代理合同。

### Changed

- 当前仓库冻结为单经营主体、自营业务模型；多商户、平台分账和 Go 异构服务转入未来
  独立《素简记 Pro》；
- 前端由逐页样式迁移为 tokens → primitives → domain components → page composition；
- 发布与回退使用不可变镜像标签，不在故障现场重新构建旧版本。

### Fixed

- 修复容器构建遗漏根 `tsconfig.base.json`，避免宿主机构建通过而镜像构建失败；
- 清除演示夹具中的 `M4 Admin`、`V6.4.3` 等内部阶段标签，并新增失败关闭门禁；
- 修复 Vite preview 与真实 Nginx 404、缓存行为不可等价的问题，改由真实容器裁决。

### Verified

- 后端正式发布冻结基线：436 tests；最近独立 PMD 为 0 违规、SpotBugs Priority 1 为 0；
- 前端 V7.3 基线：303 个单元/契约测试、60 个开发态 E2E、3 个生产构建 E2E；
- V7.4 真实双 Nginx 容器：健康检查通过，`index.html` no-store，哈希资源 immutable，
  稳定图片非 immutable，缺失资源 404，同源 API 200；
- `v1.0.0-rc.1 → v1.0.0-rc.0 → v1.0.0-rc.1` 三次实际镜像 ID 切换均使用
  `--no-build --pull never`。
