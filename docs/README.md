# PlainJournal 文档导航

根目录 `README.md` 只承担项目入口。这里按“当前事实、设计基线、历史证据”组织完整
文档，避免把 100 余份阶段报告平铺到仓库首页。

## 先看这里

- [当前验证摘要](verification-summary.md)：当前测试、静态门禁和真实证据基线；
- [验证索引](verification-index.md)：UI Demo、Core Smoke、Full Lab 三档入口；
- [项目历史](project-history.md)：阶段演进、Git 公开历史和版本边界；
- [项目总计划](00-project-master-plan.md)：完整范围、里程碑和冻结决策；
- [产品范围](01-product-scope.md)：自营 B2C 边界与明确不做的内容；
- [服务架构](02-service-architecture.md)：服务、调用、事件和部署结构；
- [数据所有权](04-data-ownership.md)：Schema、账号和最终事实归属；
- [一致性策略](05-consistency-strategy.md)：事务、Outbox、幂等、补偿与对账；
- [版本矩阵](06-version-matrix.md)：已验证运行时和中间件版本。

## 主题分区

| 文档范围 | 内容 |
| --- | --- |
| `00`–`09` | 项目范围、架构、状态机、数据所有权、安全、版本与 Redis 降级 |
| `10`–`25` | Catalog、Inventory、Trade、Payment、Fulfillment、Marketing、售后、观测、补偿与对账 |
| `26`–`55` | M2–M7 多实例、结果未知、容量、分片、副本、归档与全量回归 |
| `56`–`69` | M8 Chat、通知、附件安全、GEO、评价、搜索、Analytics 与三层审查 |
| `70`–`80` | 前端主题、低耦合分层和真实业务页面验收 |
| `81`–`105` | 前端视觉与交付历史、响应式图片、静态部署和 v1.0.0 发布候选 |
| `quality/` | 静态分析台账、接受风险和后续门禁策略 |

阶段报告是当时工作树的证据快照，文件名、测试数字和阶段结论不会被事后重写。当前
版本、测试数和发布状态统一以 [当前验证摘要](verification-summary.md) 为准。

## 运行与交付

- [本地开发网络](07-local-development-network.md)
- [Core Smoke 冷启动指南](core-smoke.md)
- [Docker 中间件说明](../deploy/docker/README.md)
- [后端专项验证入口](../backend/README.md)
- [前端开发与演示入口](../frontend/README.md)
- [Nginx 静态部署说明](../frontend/deploy/nginx/README.md)
- [安全策略](../SECURITY.md)
- [参与贡献](../CONTRIBUTING.md)

## 文档维护规则

1. 当前数字只修改 `.github/verification-baseline.json`，再运行生成器。
2. 新文档必须加入本导航或验证索引，不继续扩充根 README。
3. 历史报告可以补充“当前状态”提示，但不改写原始测试事实。
4. 任何真实基础设施结论都必须给出脚本、资源、清理和失败边界。
5. 文档相对链接由 `node tools/check-markdown-links.mjs` 自动检查。

