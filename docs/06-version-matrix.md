# 技术版本矩阵

## 1. 已验证的本机环境

| 组件 | 当前版本 | 状态 |
| --- | --- | --- |
| JDK | 17.0.12 LTS | 固定，可用于 Spring Boot 3 |
| Maven | 3.9.11 | 固定 |
| Git | 2.53.0 | 固定 |
| Node.js | 18.20.8 | 仅保留旧项目；新前端启动前升级 |
| pnpm | 11.7.0 | 可用，需配合新 Node 验证 |
| Docker Engine | 29.6.1 | 已验证 |
| Docker Compose | 5.3.0 | 已验证 |

## 2. 已运行的中间件

| 组件 | 版本/镜像 | 用途 |
| --- | --- | --- |
| MySQL | 8.4.10 | 各服务独立 schema、最终业务事实 |
| Redis | 7.4.9 Alpine | 缓存、限流、会话、热点准入、GEO |
| Nacos | 3.2.2 | 注册发现与配置管理 |
| RocketMQ | 5.3.2 | 领域事件、延迟消息、异步解耦 |
| MinIO | `RELEASE.2025-06-13T11-33-47Z` | 商品、聊天、物流和售后文件 |

以上版本已在本机完成启动和功能检查，配置见 `deploy/docker`。

## 3. 已冻结应用基线

以下精确版本已在 `poc/middleware-compatibility` 中通过真实中间件测试。

| 技术 | 冻结版本 | 选择说明 |
| --- | --- | --- |
| Spring Boot | 3.5.16 | JDK 17，使用官方 Parent/BOM |
| Spring Cloud | 2025.0.3 | 与 Boot 3.5 发布线匹配 |
| Spring Cloud Alibaba | 2025.0.0.0 | 与 Cloud 2025.0.x、Boot 3.5.x 匹配 |
| MyBatis-Plus | 3.5.17 | 使用 Spring Boot 3 starter |
| RocketMQ Java Client | 5.2.0 | 直接使用 gRPC Client，并封装在基础设施适配层 |
| MinIO Java SDK | 9.0.3 | 已按 9.x API 完成上传和签名 URL 验证 |
| Spring Security | 由 Boot BOM 管理 | 不手工覆盖版本 |
| Resilience4j | 由兼容 BOM/Starter 管理 | 超时、隔离、熔断，不用于掩盖业务错误 |
| springdoc-openapi | 与 Boot 3 匹配的稳定版 | 生成服务 OpenAPI 文档 |
| Testcontainers | 由 BOM 管理 | MySQL、Redis、RocketMQ 集成测试 |

RocketMQ Spring Starter 2.3.6 可以完成收发，但在 Spring Boot 3.5 下会产生 BeanPostProcessor 提前初始化告警。本项目不依赖其注解编程模型，因此采用直接 Java Client + 自有基础设施适配层，边界更清楚。

## 4. 前端候选基线

| 技术 | 目标 |
| --- | --- |
| Node.js | 22.x LTS 候选，替换当前 Node 18 |
| Vue | Vue 3 + TypeScript |
| 构建工具 | Vite，精确版本随 Node 一起验证 |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| UI | Element Plus，业务主题轻量定制 |
| 请求层 | Axios 或基于 OpenAPI 生成的类型化客户端 |
| 测试 | Vitest + Vue Test Utils + Playwright |

后台重点是效率和信息密度；商城前台贴合电商主题即可，不复用“暗室藏书”的沉浸式设计成本。

## 5. 精确版本冻结结果

最小验证工程已完成以下项目：

1. Maven BOM 可解析，Spring Framework 统一为 6.2.19，SLF4J 统一为 2.0.18。
2. Spring 应用上下文可启动。
3. Nacos 配置发布/读取和临时服务注册/发现可用。
4. MyBatis-Plus 可连接 MySQL 8.4 并执行测试表 CRUD。
5. Redis 连接和键读写可用。
6. RocketMQ 5.3.2 Proxy 可完成 gRPC 生产、消费和确认。
7. MinIO 可完成私有上传、签名 URL、下载和对象删除。

`mvn clean verify` 已连续运行通过；当前测试结果为 6 项、0 失败、0 错误。

任意一项失败时，Spring Boot、Spring Cloud 和 Spring Cloud Alibaba 必须按发布列车整体调整，禁止只强行覆盖其中一个依赖版本。

## 6. 暂不引入

- Seata/TCC：首期使用本地事务、Outbox、幂等和补偿。
- Elasticsearch：商品基本查询完成后再引入，避免过早维护双写。
- Prometheus/Grafana/OpenTelemetry：核心服务能运行后增加，届时单独确定版本组合。
- Kubernetes：本地 Compose 和服务边界稳定后再评估。

## 7. 官方核验入口

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Cloud project](https://spring.io/projects/spring-cloud)
- [Spring Cloud Alibaba repository](https://github.com/alibaba/spring-cloud-alibaba)
- [MyBatis-Plus documentation](https://baomidou.com/)
- [Apache RocketMQ documentation](https://rocketmq.apache.org/docs/)
- [Nacos documentation](https://nacos.io/en/docs/latest/)
