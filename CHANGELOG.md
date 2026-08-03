# 变更记录

本文件记录《素简记》可交付版本的主要变化。阶段实验的完整时间线和验证证据仍保存在
`docs/`，这里不重复罗列每一条内部实现记录。

## [Unreleased]

- 正式版本发布后的明确缺陷修复将记录在这里。

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
