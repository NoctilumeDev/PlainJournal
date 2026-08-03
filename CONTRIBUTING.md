# 参与贡献

感谢关注 PlainJournal。本仓库已经冻结为单经营主体、自营 B2C 的增强型分布式练习
平台；当前接受明确缺陷、可复现性、测试、文档、安全和性能边界改进，不再扩展新的
大型业务域。

## 开始之前

1. 阅读根目录 `README.md`、`docs/README.md` 和 `SECURITY.md`。
2. 缺陷先使用 Issue 模板给出最小复现；安全问题使用 Private Vulnerability Reporting。
3. 不要提交真实密码、Token、Cookie、用户数据、代理配置或本机绝对路径。
4. 大范围架构修改应先说明数据所有者、事务边界、兼容性和回滚方案。

## 本地门禁

后端：

```bash
cd backend
./mvnw clean verify
./mvnw org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
cd ..
node tools/check-backend-boundaries.mjs
```

Windows 可使用 `mvnw.cmd`。前端：

```bash
cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm check
```

仓库门禁：

```bash
node --test tools/*.test.mjs
node tools/check-markdown-links.mjs
node tools/render-verification-summary.mjs --check
git diff --check
```

真实 MySQL、Redis、Nacos、RocketMQ、MinIO、OpenSearch、多实例和故障注入验证按
`docs/verification-index.md` 串行执行。普通 Pull Request 不要求运行全部 Full Lab，
但必须说明没有执行的层级。

## 变更约束

- 服务只写自己的 Schema，不共享 Mapper、Entity 或数据库账号。
- 跨服务一致性继续使用本地事务、Outbox、幂等消费、补偿与对账。
- `platform-common` 只承载稳定的技术合同，不放业务状态机或领域规则。
- 前端遵守现有分层门禁；`v1.0.x` 不进行视觉系统重构。
- 不为“技术栈更丰富”同时引入第二套消息队列、搜索引擎或分布式事务框架。
- 历史报告保留当时数字；当前结果只更新验证基线和生成摘要。

## 提交与 Pull Request

提交应按可审查主题拆分，例如 `ci: add public verification gates`、`docs: unify current
verification baseline`。Pull Request 需填写影响边界、验证证据和回滚方式；修改 API、
事件或数据库语义时，还需说明新旧版本是否允许并行运行。

