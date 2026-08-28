# PlainJournal 文档导航

`docs/` 根目录只保留当前有效的产品、架构、运行和验证规范。逐批施工记录由 Git
历史保存，仓库只额外保留少量不可替代的终局验收证据。

## 首要入口

- [项目总计划](00-project-master-plan.md)：当前范围、M0-M8 结果和冻结规则；
- [参考基线与 Pro 边界](reference-baseline-and-pro-boundary.md)：16GB 单机方法、
  并发数字解释，以及 PlainJournalPro 职责；
- [当前验证摘要](verification-summary.md)：版本、测试、覆盖率和真实证据事实源；
- [验证索引](verification-index.md)：UI Demo、Core Smoke、Full Lab 三档入口；
- [32 GiB 扩展验收协议](32gib-extended-validation-runbook.md)：内存升级后的 fresh
  bootstrap、默认 Core Smoke、代表服务三实例、容量阶梯、故障恢复和清理；
- [项目历史](project-history.md)：阶段演进、Git 公开历史和版本边界；
- [验收证据](evidence/README.md)：M0-M8 三层验收与正式发布前的工程冻结快照。

## 当前设计

### 产品与架构

- [产品范围](01-product-scope.md)
- [服务架构](02-service-architecture.md)
- [核心状态机](03-core-state-machines.md)
- [数据所有权](04-data-ownership.md)
- [一致性策略](05-consistency-strategy.md)
- [技术版本矩阵](06-version-matrix.md)

### 运行与安全

- [本地开发网络](07-local-development-network.md)
- [身份认证与令牌安全](08-identity-security.md)
- [Redis 流量防护与降级](09-redis-traffic-protection.md)

### 领域服务

- [Catalog](10-catalog-service.md)
- [Inventory](11-inventory-service.md)
- [Trade](12-trade-service.md)
- [Payment](13-payment-service.md)
- [Fulfillment](14-fulfillment-service.md)
- [Marketing](15-marketing-service.md)
- [售后与退款](16-after-sale-refund.md)

### 工程治理

- [技术采纳与单机实验边界](17-technology-adoption-matrix.md)
- [指标、看板与告警](18-observability-and-alerting.md)
- [补偿治理](19-compensation-governance.md)
- [支付与退款对账](20-payment-reconciliation.md)
- [库存与退货回补对账](21-inventory-reconciliation.md)
- [同步调用韧性](22-synchronous-call-resilience.md)
- [Trade 调度隔离](23-trade-scheduling-isolation.md)
- [分布式追踪](24-distributed-tracing.md)
- [Trade 与 Fulfillment 对账](25-trade-fulfillment-reconciliation.md)
- [SpotBugs 基线与分类策略](quality/spotbugs-triage.md)

## 运行入口

- [Core Smoke 冷启动指南](core-smoke.md)
- [Docker 中间件说明](../deploy/docker/README.md)
- [后端专项验证](../backend/README.md)
- [前端开发与演示](../frontend/README.md)
- [Nginx 静态部署](../frontend/deploy/nginx/README.md)
- [安全策略](../SECURITY.md)
- [参与贡献](../CONTRIBUTING.md)

## 维护规则

1. 当前版本和验证数字只修改 `.github/verification-baseline.json`，再运行生成器。
2. 根目录文档只描述当前有效规则，不复制逐批施工流水。
3. 过程记录依赖 Git 历史追溯；只有终局验收矩阵可以进入 `evidence/`。
4. 新的现行规范必须加入本导航，新的验收快照必须说明日期和适用版本。
5. 真实基础设施结论必须给出脚本、资源、清理和失败边界。
6. 所有相对链接由 `node tools/check-markdown-links.mjs` 检查。
