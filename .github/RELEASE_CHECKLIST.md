# PlainJournal 发布清单

本清单用于仓库所有者冻结版本。自动化不得自行提交、推送或创建标签；Release
Workflow 只在已存在标签时打包并创建或更新 GitHub Release。

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
- [ ] `v1.0.4` 标签、Release Notes、ZIP、SHA-256、manifest 和 SPDX SBOM 已公开。
