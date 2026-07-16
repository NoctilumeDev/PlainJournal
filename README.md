# 素简记（Plain Journal）

> 把复杂留给系统，把简单交给用户。

素简记是一个从自营 B2C 起步、面向多实例集群、高并发、数据规模化和多商户演进的分布式电商平台。项目使用 Spring Boot 3、JDK 17 和 Vue 3，在个人开发环境能够承受的范围内，通过真实中间件、故障恢复和量化测试验证分布式机制，而不是复刻任何既有商城。

项目已经完成架构设计、本地中间件验证、后端基础、Identity、Catalog、Inventory、Trade、Payment、Fulfillment 和 Marketing 垂直切片，以及可降级的 Redis 流量保护。注册登录、JWT/RBAC、刷新令牌轮换、商品发布、私有商品媒体、数据库条件库存预占、预占生命周期、Flyway、Outbox、地址所有权与不可变快照、可恢复取消、支付验签与回调幂等、消息幂等消费、优惠券/红包/补贴叠加、地区资格、价格分摊快照和网关路由均已通过真实中间件验证。

当前坐标：营销价格基础已完成，下一阶段为整单退货退款。

## 项目与设计基线

- [项目计划书](docs/00-project-master-plan.md)
- [产品范围](docs/01-product-scope.md)
- [服务架构](docs/02-service-architecture.md)
- [核心状态机](docs/03-core-state-machines.md)
- [数据所有权](docs/04-data-ownership.md)
- [分布式一致性策略](docs/05-consistency-strategy.md)
- [版本矩阵](docs/06-version-matrix.md)
- [本地开发网络](docs/07-local-development-network.md)
- [身份与令牌安全](docs/08-identity-security.md)
- [Redis 流量保护与降级](docs/09-redis-traffic-protection.md)
- [Catalog 服务](docs/10-catalog-service.md)
- [Inventory 服务](docs/11-inventory-service.md)
- [Trade 服务](docs/12-trade-service.md)
- [Payment 服务](docs/13-payment-service.md)
- [Fulfillment 服务](docs/14-fulfillment-service.md)
- [Marketing 服务](docs/15-marketing-service.md)

## 本地中间件

参见 [deploy/docker/README.md](deploy/docker/README.md)。运行数据保存在 `D:/Middleware/ecommerce-platform`，本地凭据不进入版本库。

在搭建或升级业务服务前，可运行 [`poc/middleware-compatibility`](poc/middleware-compatibility) 中的真实中间件兼容性验证。

## 后端基础

多模块构建、服务端口和真实 Nacos 服务发现冒烟说明参见 [backend/README.md](backend/README.md)。
