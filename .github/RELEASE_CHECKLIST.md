# PlainJournal v1.0 发布清单

本清单用于仓库所有者人工发布。自动化脚本不得自行提交、推送、创建标签或发布
GitHub Release。

## 1. 法律与仓库身份

- [x] 仓库所有者选择并审阅 Apache License 2.0，根目录 `LICENSE` 已落地。
- [x] 配置公开仓库 `https://github.com/NoctilumeDev/PlainJournal` 为 GitHub `origin`。
- [x] 镜像默认 `org.opencontainers.image.source` 已改为 `https://github.com/NoctilumeDev/PlainJournal`。
- [x] 检查 Git 历史和工作树；未发现高风险凭据，运行目录、日志和真实 `.env` 均未暂存。

## 2. 代码冻结

- [x] 后端执行 `mvn clean verify`：100 份 Surefire 报告、436 tests 全通过。
- [x] 前端使用 `D:\Node.js\current\node.exe` 和 pnpm 11 执行 `pnpm check`。
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

## 5. 人工发布

- [ ] 从冻结提交构建最终 `v1.0.0` 镜像，并核对 OCI revision。
- [ ] 创建带注释 Git 标签 `v1.0.0`。
- [ ] 推送默认分支和标签。
- [ ] 创建 GitHub Release，附上 CHANGELOG 摘要、截图、启动说明和已知边界。
- [ ] 发布后从空目录重新克隆并完成一次文档驱动的冷启动验收。
