# 变更记录

本文件记录《素简记》可交付版本的主要变化。阶段实验的完整时间线和验证证据仍保存在
`docs/`，这里不重复罗列每一条内部实现记录。

## [Unreleased]

### Changed

- 新增 32 GiB 扩展验收协议，冻结 fresh bootstrap、默认 Core Smoke、代表服务三实例、
  资源有界并发阶梯、故障恢复、浏览器和清理顺序；该协议保持 `PLANNED / DEFERRED`，
  不把脚本能力或 16 GiB 宿主停止线冒充为新的运行通过证据。

### Fixed

- 修复 fresh Docker 环境在第一次 Compose 启动前缺少合法 Nacos JWT 签名密钥的问题：
  bootstrap 现在只在本地生成或严格校验该密钥，迁移旧占位值，拒绝非法、过短或重复配置，
  并通过 Nacos 专用的 ignored runtime env 文件阻断宿主同名变量覆盖；既有合法自定义密钥
  保持不变。

## [1.0.9] - 2026-08-26

> 收拢 `v1.0.8` 之后已合入的兼容依赖、Action 更新和不可覆盖发布治理；不增加业务域，
> 不修改数据库结构、交易状态机、公开 API 或前端视觉。

### Changed

- Vite 更新到 8.2.2，Vitest 与 `@vitest/coverage-v8` 更新到 4.1.11，并同步刷新锁定的
  兼容传递依赖；
- CodeQL Action 更新到新的 v4 完整提交 SHA；
- 稳定版 Release 改为严格追加写入：标签对应的 GitHub Release 已存在时，工作流
  直接失败，不再编辑说明、覆盖或替换既有附件；
- 技术版本矩阵与依赖锁重新对齐，`v1.0.8` 的公开发布事实也已回写项目时间线与清单。

### Verification boundary

- `v1.0.9` 维护基线已通过仓库结构、文档、版本、Compose 和发布材料门禁；后端 436 项测试、
  72.42% 行覆盖率和 12 份零违规 PMD 报告通过，前端 323 项单元/契约测试、60 项开发态
  E2E、3 项生产构建 E2E、70.16% 行覆盖率与依赖审计通过；
- 候选 PR 与对应 `main` 合并提交的 CI、Security、CodeQL 和 Online Preview 已通过；
  正式标签只允许指向重新通过远端门禁的 `released` 提交，Release 资产由标签工作流
  生成并按 manifest 独立回读；
- 既有真实中间件、三实例、容量和 Chromium F12/CDP 证据保持原验证边界，不把本次
  维护回归冒充为重新执行的真实基础设施实验。

## [1.0.8] - 2026-08-21

> 收拢 `v1.0.7` 之后已经合入的维护修复、兼容依赖更新和公开发布坐标；不增加业务域，
> 不修改数据库结构、交易状态机或前端视觉。

### Fixed

- 将传递依赖 `nanoid` 固定到 `3.3.18`，修复新的安全公告并恢复完整 pnpm 依赖图
  0 已知漏洞；
- Trade 售后集成测试夹具改用数据库时钟构造时间边界，消除应用时钟与数据库时钟
  漂移造成的偶发失败；
- CI 与 Security Workflow 显式响应 PR 更新和 merge queue 事件，避免只在初次打开
  PR 时执行旧检查结果。

### Changed

- CodeQL Action 更新到新的 v4 完整提交 SHA；
- Maven Wrapper 更新到 3.9.16，RocketMQ Java Client 更新到 5.2.1，JaCoCo 更新到
  0.8.15，Trade 使用的 Apache Commons Lang 更新到 3.20.0；
- Vue、Pinia、Vite、`vue-tsc`、Playwright 与 axe Playwright 集成更新到已通过完整
  前端门禁的兼容补丁版本；
- Spring Cloud 2025.1 / Spring Cloud Alibaba 2025.1 平台升级未纳入本版本：该组合
  需要 Spring Framework 7 的 HTTP Service Group API，与当前 Spring Boot 3.5.16 /
  Spring Framework 6.2.19 基线不兼容，继续保持同一发布列车而不做强制覆盖。

### Verification

- 后端 14 个 Reactor 模块、100 份 Surefire 报告、436 个测试和 PMD 全部通过，
  0 失败、0 错误、0 跳过；JaCoCo 0.8.15 重新计数后的聚合行覆盖率为 72.40%；
- 前端 323 个单元/契约测试、60 个开发态 E2E、3 个生产构建 E2E、类型检查、两端
  构建、axe、分层、交付、部署和发布材料门禁全部通过，聚合行覆盖率为 70.16%；
- `pnpm audit --audit-level moderate` 报告 0 已知漏洞；既有真实中间件、三实例、容量
  和 Chromium F12/CDP 证据按原验证日期保留，不把本次代码回归冒充为重新执行的
  真实基础设施实验。

## [1.0.7] - 2026-08-08

> 修正正式标签与机器可读验证状态的顺序，不增加业务域，不修改数据库、并发实现或
> 前端视觉。

### Fixed

- 发布标签校验现在要求验证基线已经处于 `released`，`release-candidate` 只能用于
  PR 和合并前检查，不能再创建正式标签；
- `v1.0.6` 标签保持不可变；其发布附件和运行结果仍有效，但标签提交内的机器状态仍为
  `release-candidate`，由 `v1.0.7` 以干净的正式发布事实取代。

## [1.0.6] - 2026-08-08

> 在不改写 `v1.0.5` 标签的前提下修复发布门禁暴露的 E2E 同步竞态，并重新发布同一
> M0-M8 参考基线加固内容；不增加业务域，不进行前端视觉重构。

### Fixed

- V5“丢失下单响应后使用原请求安全重试”E2E 不再用原本就可见的未知状态作为完成
  信号，改为等待第二个 POST 已被路由观察，再校验两次请求使用同一幂等键；
- 该 V5 配置在本机串行重复 10 轮、30/30 场景通过。

### Release

- `v1.0.5` 标签在 Release 工作流中暴露上述竞态，工作流失败且未创建 GitHub Release
  或发布附件；标签保持不可变，由 `v1.0.6` 取代正式发布。

## [1.0.5] - 2026-08-08

> 加固 M0-M8 参考基线的代码所有权、可移植验证和 16GB 资源边界，不增加业务域，
> 不进行前端视觉重构。该标签的 Release 工作流被 E2E 同步竞态阻断，未创建正式
> GitHub Release，由 `v1.0.6` 取代。

### Changed

- Chat、Identity 和 Trade 的接口层改为依赖应用端口，基础设施实现保持在适配层；
  架构门禁新增接口到基础设施、跨服务导入、事务边界和 schema 所有权检查；
- 验证脚本移除个人工作站绝对路径，新增仓库内 Windows 宿主机预检、串行中间件隔离、
  固定随机种子的异步 HTTP runner 和带资源停止线的容量阶梯；
- 三实例清理在 MySQL 前置条件缺失时也能释放已启动资源，不把启动前失败留成后续批次
  的污染变量；
- 当前文档明确区分 `1000` 次请求、`100` 并发的已发布证据与尚未证明的
  `1000` 同时在途请求。

### Security

- 将传递依赖 `nanoid` 统一固定到 `3.3.17`，修复
  `GHSA-2v37-7h3g-55p8`，完整 pnpm 依赖图审计恢复为 0 已知漏洞。

### Verification

- 后端 14 个 Reactor 模块、436 个测试、PMD 和 72.43% 聚合行覆盖率通过；
- 前端 322 个单元/契约测试、70.16% 聚合行覆盖率、60 个开发态 E2E 和 3 个生产构建
  E2E 通过；
- 16GB Windows 主机在仅启动 MySQL、Nacos 和 RocketMQ 组后达到 83.1% 内存占用、
  2.67 GiB 可用内存，正确越过 82% / 3 GiB 停止线，未继续启动 Trade 或流量；
- PR 的 CI、Dependency review、pnpm audit 以及 Java、JavaScript/TypeScript CodeQL
  均通过。

## [1.0.4] - 2026-08-06

> 收拢 `v1.0.3` 发布后发现的两项真实边界缺陷，不增加业务域，也不进行前端视觉重构。

### Fixed

- 结果未知的物流轨迹在刷新后只持久化履约编号、外部事件 ID、创建时间和权威读取标记；
  轨迹说明、地点与坐标均不进入 `localStorage`，同一页面会话内仍可使用内存快照原样
  重试，刷新后只能读取 Fulfillment 权威事实确认结果。
- 通知邮件人工恢复以 `notification_delivery` 行锁作为唯一串行化边界，并显式使用
  `READ_COMMITTED`；相同 `commandId` 的并发请求在等待胜出事务提交后读取审计结果，
  不再因看见 `RETRY` 状态却看不见审计记录而被错误拒绝。

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
