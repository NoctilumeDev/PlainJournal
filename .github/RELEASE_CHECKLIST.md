# PlainJournal 发布清单

本清单用于仓库所有者冻结版本。自动化不得自行提交、推送或创建标签；Release
Workflow 只在已存在标签时打包，并且仅在 GitHub Release 尚不存在时创建；重跑必须
拒绝覆盖既有 Release 与附件。

## 1. 法律与仓库身份

- [x] 仓库所有者选择并审阅 Apache License 2.0，根目录 `LICENSE` 已落地。
- [x] 配置公开仓库 `https://github.com/NoctilumeDev/PlainJournal` 为 GitHub `origin`。
- [x] 镜像默认 `org.opencontainers.image.source` 已改为 `https://github.com/NoctilumeDev/PlainJournal`。
- [x] 检查 Git 历史和工作树；未发现高风险凭据，运行目录、日志和真实 `.env` 均未暂存。

## 2. 代码冻结

- [x] 后端执行 `mvn clean verify`：100 份 Surefire 报告、436 tests 全通过。
- [x] 前端使用 Node.js 24.14.0 和 pnpm 11.9.0 执行 `pnpm check`。
- [x] 执行 Markdown 链接、PowerShell AST、Compose 解析和 `git diff --check`。
- [x] 核对没有 Java、Vite、Mock、Playwright 或 PlainJournal 容器残留。

## 3. 真实发布候选

- [x] 顾客端与管理端镜像可从干净构建上下文构建。
- [x] OCI 版本、提交、构建时间、来源、标题和描述已写入镜像。
- [x] 双容器 HEALTHCHECK 为 healthy。
- [x] `index.html` no-store；哈希资源 immutable；稳定图片非 immutable。
- [x] 缺失 `/assets`、`/images` 返回 404；同源 `/api` 返回真实上游结果。
- [x] 两个不可变标签完成 `--no-build --pull never` 回退与恢复。

## 4. 展示与文档

- [x] README 包含成品截图、项目边界、快速演示和三层证据入口。
- [x] `CHANGELOG.md`、`SECURITY.md` 和 V7.4 收口报告已完成。
- [x] 公开演示账号明确标注为夹具，不冒充生产账号。
- [x] 多商户与 Go 异构服务明确转入未来独立《素简记 Pro》。

## 5. v1.0.0 公开发布

- [x] 从冻结提交构建最终 `v1.0.0` 镜像，并核对 OCI revision。
- [x] 创建并推送 Git 标签 `v1.0.0`。
- [x] 推送默认分支。
- [x] 创建 GitHub Release，包含 CHANGELOG 摘要、截图、启动说明和已知边界。
- [ ] 发布后从空目录重新克隆并完成一次文档驱动的 Core Smoke 冷启动验收。

## 6. v1.0.1 工程加固

- [x] CI、Security、Pages 和 Release Workflow 在公开仓库通过。
- [x] 后端/前端版本、验证摘要和文档入口保持一致。
- [x] Maven Wrapper、跨平台 UI Demo/E2E 和后端架构门禁通过。
- [x] Release 附带自定义 ZIP、SHA-256、manifest 和 SPDX SBOM。
- [x] 从冻结提交完成最终本地回归、浏览器/F12 和资源清理。

## 7. v1.0.2 验收收口

- [x] 后端聚合行覆盖率不低于 70%，每个业务模块不低于 65%。
- [x] 前端四包聚合行覆盖率不低于 70%，管理端关键路由和组件测试通过。
- [x] Dependabot 忽略常规 Major 升级并将 Minor/Patch 按生态分组。
- [x] Actions 固定完整 Commit SHA，Release 重新执行 CI 并核验同一提交的 Security。
- [x] 标签提交属于 `main`，发布材料、人工 Release Notes 和验证摘要一致。
- [x] 完成真实基础设施、三实例、多角色浏览器链路、F12 和最终资源清理。

## 8. v1.0.3 缺陷补丁

- [x] 后端和前端版本均为正式 `1.0.3`，发布标签内不存在 `-SNAPSHOT`。
- [x] CI 检查真实 PR/push 变更集，Release 只接受严格稳定版标签。
- [x] 评价同键并发回归稳定通过，真实 MySQL 评价专项完成。
- [x] Dependency Graph、安全更新、私密漏洞报告和 Dependabot 标签已启用。
- [x] 当前规范与精选验收证据分区，逐批日志由 Git 历史保存，文档结构、索引、路径和
  可漂移数字进入 CI 门禁。
- [x] PlainJournal M0-M8 参考基线与独立 PlainJournalPro 的 M9+ 边界已写入文档。
- [x] 完整 CI、Security、Release 附件与本机资源清理通过。

## 9. v1.0.4 发布后边界补丁

- [x] 结果未知的履约轨迹不再把说明、地点或坐标持久化到浏览器本地存储。
- [x] 通知邮件人工恢复以投递行锁和 `READ_COMMITTED` 保证同命令并发回放可见性。
- [x] 本地后端 14 模块、436 个测试、覆盖率和仓库文档门禁通过。
- [x] PR 与 `main` 的 CI、Security 和 CodeQL 通过，公开代码扫描告警为 0。
- [x] `v1.0.4` 标签、Release Notes、ZIP、SHA-256、manifest 和 SPDX SBOM 已公开。

## 10. v1.0.5 参考基线加固

- [x] 接口、应用和基础设施层的权责收口，跨服务导入、事务和 schema 所有权进入门禁。
- [x] 宿主机预检、串行中间件隔离、固定种子负载与容量阶梯均为仓库内可移植入口。
- [x] 16GB 资源停止线正确阻止超预算组合，不宣称未经证明的 1000 同时在途结果。
- [x] 后端 436 个测试、前端 322 个单元/契约测试及完整浏览器链路通过。
- [x] `nanoid` 安全公告已修复，PR 的 CI、Security 和 CodeQL 全绿。
- [x] `v1.0.5` 标签的 Release 工作流暴露 V5 E2E 同步竞态，未创建正式 Release；
  标签保持不可变并由后续补丁取代。

## 11. v1.0.6 发布门禁修复

- [x] V5 丢响应重试等待第二个 POST 网络事实，不再依赖已经可见的旧 UI 状态。
- [x] V5 配置本机串行重复 10 轮、30/30 场景通过。
- [x] PR 与 `main` 的 CI、Security、CodeQL 和 Online Preview 全绿。
- [x] `v1.0.6` 标签、Release Notes、ZIP、SHA-256、manifest 和 SPDX SBOM 已公开。

## 12. v1.0.7 发布事实修复

- [x] `release-candidate` 在 PR 阶段仍可验证，但不能再通过正式标签门禁。
- [x] 后端、前端、验证基线、Changelog 和 Release Notes 统一为 `1.0.7`。
- [x] `v1.0.6` 标签保持不可变，历史状态差异在 Changelog 和项目时间线中如实记录。
- [x] PR、`main`、Security、Online Preview 和 Release 工作流全部通过。
- [x] `v1.0.7` ZIP、SHA-256、manifest 和 SPDX SBOM 已公开。

## 13. v1.0.8 维护发布

- [x] `v1.0.7` 后已合入的 `nanoid` 安全修复、售后数据库时钟夹具和 PR / merge queue
  Workflow 触发器进入同一候选。
- [x] 五个 Dependabot 组逐项裁决；兼容的 Action、Maven、Java 和前端依赖进入候选，
  Spring Cloud 2025.1 / Spring Cloud Alibaba 2025.1 因发布列车不兼容明确暂缓。
- [x] 后端 436 个测试、PMD、72.40% 聚合行覆盖率与前端 323 + 60 + 3 测试、
  70.16% 聚合行覆盖率在新依赖图上通过。
- [x] 后端、前端、版本矩阵、验证基线、Changelog 和 Release Notes 统一为 `1.0.8`
  验收候选。
- [x] PR、`main`、CI、Security、CodeQL 和 Online Preview 对同一候选提交全部通过。
- [x] 验证基线从 `release-candidate` 变更为 `released`，且最终提交重新通过远端门禁。
- [x] `v1.0.8` 标签解析到发布提交；Release、ZIP、SHA-256、manifest 和 SPDX SBOM
  已回读对齐该标签，后续 Workflow 重跑会拒绝覆盖既有 Release。

## 14. v1.0.9 追加写发布治理

- [x] Vite 8.2.2、Vitest 4.1.11、CodeQL v4 Action 与锁定传递依赖通过完整门禁。
- [x] 稳定 Release 已存在时工作流明确失败，且不存在 edit、upload 或 clobber 路径。
- [x] 后端、前端、版本矩阵、验证基线、Changelog 和 Release Notes 统一为 `1.0.9`。
- [x] 本地仓库、后端、前端、浏览器、构建、Compose 与发布材料门禁全部通过。
- [x] 候选 PR 提交 `bcf02e2` 与对应 `main` 合并提交 `d891143` 的 CI、Security、
  CodeQL 和 Online Preview 全部通过。
- [x] 正式发布提交 `0cfe97e` 在打标签前重新通过 CI、Security、CodeQL 和
  Online Preview，验证基线处于 `released`。
- [x] `v1.0.9` 注解标签解析到 `0cfe97e`；Release、ZIP、SHA-256、manifest 和
  SPDX 2.3 SBOM 已回读一致，ZIP SHA-256 为
  `122c21d3a3552924aaea2e50998c07921da7b1e617c53379402d7fbaa40b6df5`。

## 15. v1.0.10 Fresh Bootstrap 与宿主边界补丁

- [x] 公开 `.env.example` 不再携带 Nacos 3.2.2 无法接受的占位 token；bootstrap 在
  Compose 首次解析前生成或严格校验 literal canonical Base64 密钥。
- [x] 专用 ignored runtime env 阻断宿主同名变量覆盖；重叠准备、大小写 `EXPORT` 差异
  和失败后旧 runtime 复用均有回归门禁。
- [x] 16 GiB 本轮停止事实与历史发布证据、未来 32 GiB 协议分层记录；32 GiB、三实例、
  容量和故障恢复仍未由本次维护回归执行。
- [x] 产品修复合并提交 `1453aaaf6746700c1d75e4f9d26f28f95bc4d599` 的 CI、Security
  和 Online Preview 已通过。
- [x] 后端、前端、验证摘要、Changelog 和 Release Notes 已统一为 `1.0.10`
  正式发布基线。
- [x] 候选 PR 提交 `df80c04` 与候选合并提交 `cf05c0c` 的 CI、Security、CodeQL 和
  Online Preview 全部通过。
- [x] 正式发布提交 `52f7de6` 在打标签前重新通过 CI、Security、CodeQL 和
  Online Preview，验证基线处于 `released`。
- [x] `v1.0.10` 注解标签对象 `c9ffdc0` 解析到 `52f7de6`；Release、ZIP、SHA-256、
  manifest 和 SPDX 2.3 SBOM 已回读一致，ZIP SHA-256 为
  `e8a2dc3f9c263bd85ea8f422b07f9eb7b33ec4a4d42269cb05efb19009409884`。

## 16. v1.1.0 前端轻量化第一轮

- [x] 顾客端 20 条、管理端 13 条真实路由完成第一轮桌面与移动端布局闭环；第 34 个
  目标页尚未定义，不计入本次完成范围。
- [x] 页面状态与业务事实留在页面和实体切片，共享层只保留纯布局器；顾客端、管理端
  与视觉文档互不以页面级样式覆盖。
- [x] 功能模块图、系统架构图、页面注册表、桌面/移动基线与漂移检查进入仓库门禁。
- [x] 第一轮前端对象 `1937f4136a16dc2ec27d618c35e50739ed5aed0d` 的 CI、Security 和
  Online Preview 通过；28 条分层边界、327 项单元与契约测试、61 项开发态 E2E、
  3 项生产构建 E2E 和 73.9% 聚合行覆盖率通过。
- [x] `v1.0.10` 发布对象与历史运行证据保持不可变；本轮不声称重新执行真实中间件、
  容量、三实例、故障恢复或 32 GiB 延期协议。
- [x] 后端、前端、候选记录、Changelog 和 Release Notes 已统一为 `1.1.0` 候选。
- [x] 候选 PR 提交 `7ab4075f9f50127a9a2a4bfdcf67f2da6cebf6cc` 与候选合并提交
  `aee901172e6224814177800eb0b90fb3bc80ed67` 的 CI、Security、CodeQL 和 Online
  Preview 全部通过。
- [ ] 候选记录提升为 `released`，且正式发布提交重新通过远端门禁。
- [ ] `v1.1.0` 注解标签、Release、ZIP、SHA-256、manifest 和 SPDX SBOM 回读一致。
