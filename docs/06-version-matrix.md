# 技术版本矩阵

## 1. 已验证的本机环境

| 组件 | 当前版本 | 状态 |
| --- | --- | --- |
| JDK | 17.0.12 LTS | 固定，可用于 Spring Boot 3 |
| Maven | 3.9.16 | 固定 |
| Git | 2.53.0.windows.2 | 固定 |
| Node.js | 24.14.0 | M4/M5.5 前端与容量工具已验证 |
| pnpm | 11.9.0 | M4/M5.5 workspace 已验证 |
| Docker Engine | 29.6.1 | 已验证 |
| Docker Compose | 5.3.0 | 已验证 |

本机和 GitHub Actions 都以 Node.js 24.14.0 / pnpm 11.9.0 为当前验证坐标。仓库入口
只依赖 `PATH` 与 Corepack，不依赖某台机器的 `NODE_HOME`、盘符或 Junction。

## 2. 已运行的中间件

| 组件 | 版本/镜像 | 用途 |
| --- | --- | --- |
| MySQL | 8.4.10 | 各服务独立 schema、最终业务事实 |
| Redis | 7.4.9 Alpine | 缓存、限流、会话、热点准入、GEO |
| Nacos | 3.2.2 | 注册发现与配置管理 |
| RocketMQ | 5.3.2 | 领域事件、延迟消息、异步解耦 |
| MinIO | `RELEASE.2025-06-13T11-33-47Z` | 商品、聊天、物流和售后文件 |

以上版本已在本机完成启动和功能检查，配置见 `deploy/docker`。

按需观测 profile 使用以下固定版本，不作为交易服务常驻依赖：

| 组件 | 版本/镜像 | 用途 |
| --- | --- | --- |
| Prometheus | 3.12.0 | 安全拉取五个核心服务指标、7 天本地保留、规则计算 |
| Alertmanager | 0.32.1 | 本地告警分组、去重、静默和状态展示 |
| Grafana | 13.1.0 | 自动配置 Prometheus/Tempo 数据源与运维看板 |
| Tempo | 2.10.5 | OTLP/HTTP trace 接收、查询与 24 小时本地保留 |

Prometheus 与 Alertmanager 配置已分别通过同版本官方 `promtool` 和 `amtool` 校验；完整容器采集由 `verify-observability.ps1` 验证，不能以配置校验代替运行证据。

按需附件安全 profile：

| 组件 | 版本/镜像 | 用途 |
| --- | --- | --- |
| ClamAV | `clamav/clamav:1.5.3-debian13-slim` | M8.7 私有附件隔离区的流式恶意文件扫描、真实 EICAR 与停机恢复验证 |

ClamAV 使用 `m8-malware-scan` Profile，3 GiB 内存上限，不属于七个核心中间件常驻
基线。病毒库保存在仓库外的中间件数据目录。

按需商品搜索 profile：

| 组件 | 版本/镜像 | 用途 |
| --- | --- | --- |
| OpenSearch | `public.ecr.aws/opensearchproject/opensearch:3.7.0` | M8.11 Catalog 可重建商品搜索投影、蓝绿重建、故障降级和版本对账 |

OpenSearch 使用 `m8-search` Profile、512 MiB 堆和 1408 MiB 容器上限，不属于七个
核心中间件常驻基线。Catalog 使用 JDK `HttpClient` 调用 HTTP API，没有额外绑定
OpenSearch Java SDK。

## 3. 已冻结应用基线

以下为当前应用基线，并同步固化在 `poc/middleware-compatibility`。真实中间件验收的
执行日期与证据边界以[验证摘要](verification-summary.md)为准；`v1.0.8` 将 RocketMQ
Java Client 从 5.2.0 更新到 5.2.1，并验证 POC 可编译，但不把这次离线编译表述为
重新执行了真实中间件批次。

| 技术 | 冻结版本 | 选择说明 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | JDK 17，使用官方 Parent/BOM |
| Spring Cloud | 2025.0.3 | 与 Boot 3.5 发布线匹配 |
| Spring Cloud Alibaba | 2025.0.0.0 | 与 Cloud 2025.0.x、Boot 3.5.x 匹配 |
| MyBatis-Plus | 3.5.17 | 使用 Spring Boot 3 starter |
| RocketMQ Java Client | 5.2.1 | 直接使用 gRPC Client，并封装在基础设施适配层 |
| MinIO Java SDK | 9.0.3 | 已按 9.x API 完成上传和签名 URL 验证 |
| Spring Security | 由 Boot BOM 管理 | 不手工覆盖版本 |
| Resilience4j | 由兼容 BOM/Starter 管理 | 超时、隔离、熔断，不用于掩盖业务错误 |
| Micrometer Tracing / OpenTelemetry bridge | 由 Boot BOM 管理 | W3C 传播与 OTLP 导出；只保留一个 bridge，不绑定第二套 SDK |
| springdoc-openapi | 与 Boot 3 匹配的稳定版 | 生成服务 OpenAPI 文档 |
| Testcontainers | 由 BOM 管理 | MySQL、Redis、RocketMQ 集成测试 |

RocketMQ Spring Starter 2.3.6 可以完成收发，但在 Spring Boot 3.5 下会产生 BeanPostProcessor 提前初始化告警。本项目不依赖其注解编程模型，因此采用直接 Java Client + 自有基础设施适配层，边界更清楚。

## 4. 已冻结前端基线

| 技术 | 当前版本/方案 | 边界 |
| --- | --- | --- |
| Node.js | 24.14.0 | 工程最低要求 22.12.0 |
| pnpm | 11.9.0 | workspace 包管理器 |
| Vue | 3.5.41 | 顾客端与管理端 |
| Vue Router | 5.2.0 | URL 状态与刷新恢复 |
| Pinia | 4.0.3 | 会话、购物袋、地址、订单、支付、履约、售后与结果未知状态 |
| Vite | 8.2.1 | 两端 CSR 构建 |
| TypeScript | 6.0.3 | `vue-tsc 3.3.10` 暂不兼容 TypeScript 7 包导出；启用未使用符号门禁 |
| Vitest | 4.1.10 | 单元与组件测试 |
| Vue Test Utils | 2.4.11 | 顾客端组件测试 |
| Playwright | 1.62.1 | 两端关键浏览器流程、路由恢复与 axe 检查 |
| axe-core/playwright | 4.13.0 | serious / critical 可访问性门禁 |
| 请求层 | 原生 `fetch` + 自有类型化 API 客户端 | 不引入 Axios 双实现 |
| UI | 自有设计令牌与语义组件 | 不引入 Element Plus，不继承旧项目视觉 |

Playwright 自动化 E2E 已纳入 `pnpm check`，当前覆盖顾客主题/权益/售后/退货事实、管理端角色/履约/对账工作区，以及顾客/客服 Chat 工作区的幂等恢复和实时状态；并使用 axe-core 检查关键页面。Payment 创建响应丢失、Fulfillment 确认收货响应丢失和 Chat 真实 WebSocket/中间件闭环仍由专用真实故障脚本验证；Mock 浏览器夹具和真实中间件脚本是不同证据，不能互相替代。商城和管理端均以《素简记商城平台设计与实施计划书》为视觉与交互基线。

## 5. 精确版本冻结结果

最小验证工程已完成以下项目：

1. Maven BOM 可解析，Spring Framework 统一为 6.2.19，SLF4J 统一为 2.0.18。
2. Spring 应用上下文可启动。
3. Nacos 配置发布/读取和临时服务注册/发现可用。
4. MyBatis-Plus 可连接 MySQL 8.4 并执行测试表 CRUD。
5. Redis 连接和键读写可用。
6. RocketMQ 5.3.2 Proxy 可完成 gRPC 生产、消费和确认。
7. MinIO 可完成私有上传、签名 URL、下载和对象删除。

最小中间件兼容性 POC 的 6 项验证是早期技术准入证据，不代表整个项目测试数量。

当前项目级门禁不再在版本矩阵中手工维护。后端、前端、PMD、SpotBugs 和真实机制的
当前数字统一见 [验证摘要](verification-summary.md)，分层运行入口见
[验证索引](verification-index.md)。本节以下只冻结技术兼容性，不承担测试计数。

M0-M8 的最终代码、自动化与真实基础设施矩阵收敛在
[三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。逐批报告由 Git
历史追溯；H2 自动化回归和浏览器 Mock 夹具不能替代真实基础设施证据。

任意一项失败时，Spring Boot、Spring Cloud 和 Spring Cloud Alibaba 必须按发布列车整体调整，禁止只强行覆盖其中一个依赖版本。

## 6. 暂不引入

- Seata/TCC：当前使用本地事务、Outbox、幂等和补偿。
- Elasticsearch：当前不与 OpenSearch 双重引入；M8.11 已选择单一 OpenSearch
  投影验证搜索机制。
- OpenTelemetry Collector、Zipkin、SkyWalking：当前 Tempo 单后端已满足本机代表链路，不为组件清单增加第二条管线。
- Kubernetes：本地 Compose 和服务边界稳定后再评估。

## 7. 官方核验入口

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Cloud project](https://spring.io/projects/spring-cloud)
- [Spring Cloud Alibaba repository](https://github.com/alibaba/spring-cloud-alibaba)
- [MyBatis-Plus documentation](https://baomidou.com/)
- [Apache RocketMQ documentation](https://rocketmq.apache.org/docs/)
- [Nacos documentation](https://nacos.io/en/docs/latest/)
- [Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
- [Grafana documentation](https://grafana.com/docs/grafana/latest/)
- [Grafana Tempo documentation](https://grafana.com/docs/tempo/latest/)
