# M8 第九批：Fulfillment 物流 GEO 与可重建 Redis 投影

## 1. 批次结论

M8.9 已完成一个不改变交易状态机的物流位置垂直闭环：

- `fulfillment-service` 在 MySQL 保存全部追加式物流轨迹和每张履约单的最新位置投影；
- MySQL 8.4 使用真实 `POINT SRID 4326`、空间索引和
  `ST_Distance_Sphere` 裁决附近查询；
- Redis GEO 只保存可丢失、可重建的最新位置加速，不参与物流事实裁决；
- 顾客只能读取自己的最新位置，跨账户查询统一返回 404；
- `ADMIN/WAREHOUSE` 可以执行有界附近查询，管理员可以从 MySQL 重建 Redis GEO；
- 顾客端展示真实位置节点和坐标，管理端提供坐标轨迹录入、附近查询和缓存重建，
  但不引入外部地图依赖，也不把坐标画布伪装成真实地图。

本批没有实现多包裹、承运商签名回调、实时 GPS 流、路线规划或外部地图服务。

## 2. 正确性边界

```text
logistics_trace（全部追加事实）
          |
          | 同一 MySQL 本地事务
          v
shipment_latest_position（最新位置投影）
          |
          | AFTER_COMMIT 最佳努力写入
          v
Redis GEO（可重建加速）
```

核心规则：

1. `logistics_trace` 仍是不可覆盖的物流历史。
2. `shipment_latest_position` 是 Fulfillment MySQL 内的所有者域投影，不是 Redis
   缓存的反向复制。
3. 新位置先比较 `occurred_at`，相同时间再比较 `trace_id`；迟到的旧承运商事件不能
   覆盖更新位置。
4. 位置投影、`fulfillment_order.latest_position_trace_id` 和轨迹写入处于同一本地
   事务；事务回滚时不会留下半个位置事实。
5. Redis 更新只在事务提交后发生。Redis 写入失败不回滚 MySQL，也不伪造缓存成功。
6. 顾客读取 Redis 缺失、过期或异常时回退 MySQL，并尝试读修复。
7. 附近查询始终由 MySQL 空间事实裁决，避免缓存不完整或短暂不一致改变结果。
8. Redis 重建失败返回 `503 GEO_CACHE_UNAVAILABLE`，不修改任何物流事实。

## 3. 数据与迁移

Flyway 新增：

- `V8__create_shipment_latest_position.sql`
  - 为 `fulfillment_order` 增加最新位置轨迹和时间引用；
  - 创建 `shipment_latest_position`，保存经纬度十进制快照、轨迹引用和更新时间；
- `V9__add_mysql_spatial_position_index.java`
  - 仅在 MySQL 执行；
  - 增加 `coordinates POINT SRID 4326 NOT NULL`；
  - 创建 `idx_shipment_latest_position_coordinates` 空间索引；
  - H2 测试环境自动跳过。

本批先用真实 MySQL 探针验证了三个实现约束：

- MySQL 生成空间列不能直接声明为本批空间索引所需的 `NOT NULL`；
- 当前应用数据库账号在 binary logging 权限边界下不能创建触发器；
- `ST_GeomFromText` 必须显式使用
  `axis-order=long-lat`，避免 SRID 4326 默认轴顺序造成经纬度解释错误。

因此最终由 MySQL Repository 在同一 SQL 中同时写十进制经纬度和普通
`POINT NOT NULL` 列。H2 使用可移植 Repository，不执行 MySQL 空间函数。探针临时表
已全部删除。

## 4. 接口与权限

| Method | Path | 权限 | 语义 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/fulfillment/orders/{orderNo}/position` | 订单所有者 | 读取最新位置；无位置返回 404 |
| `GET` | `/api/v1/fulfillment/admin/geo/nearby` | `ADMIN/WAREHOUSE` | MySQL 有界附近查询 |
| `POST` | `/api/v1/fulfillment/admin/geo/cache/rebuild` | `ADMIN` | 从 MySQL 重建 Redis GEO |

附近查询限制由配置统一约束：

- 经度 `-180..180`，纬度 `-90..90`；
- 半径必须大于 0，当前最大 2,000,000 米；
- 结果数必须大于 0，当前最大 200；
- 单次缓存重建当前最多扫描 5,000 条。

## 5. Redis Key 与可观测性

Redis Key 使用环境命名空间：

```text
ecommerce:{namespace}:fulfillment:geo:latest
ecommerce:{namespace}:fulfillment:geo:position:{fulfillmentNo}
```

GEO Sorted Set 保存最新坐标，独立元数据 Key 保存节点、外部事件、轨迹和时间快照。
两者都可从 MySQL 重建。

Fulfillment 暴露低基数指标：

```text
ecommerce.fulfillment.geo.cache.operations
```

标签只包含 `operation` 与 `outcome`，覆盖读取命中、MySQL 回退、提交后写入、读修复
和重建结果；不把订单号、履约号、用户 ID 或坐标放入标签。

## 6. 自动化门禁

定向后端：

```text
platform-common       14 tests
fulfillment-service   19 tests
合计                  33 tests
```

覆盖最新位置投影、乱序旧轨迹、顾客所有权隔离、无位置 404、管理员权限、有界附近
查询和 H2 迁移兼容。

前端在 Node.js 24.14.0 / pnpm 11.9.0 下执行 `pnpm check`：

```text
Foundation Vitest    32
Storefront Vitest    51
Admin Vitest          2
Playwright E2E        4
```

两端类型检查、生产构建和关键页面 axe 检查同时通过。管理端 E2E 已覆盖附近位置结果、
MySQL/Redis 边界说明和缓存重建反馈。

本批交付时的后端阶段快照：

- `mvn clean verify`：84 份 Surefire 报告、299 tests，0 失败、0 错误、0 跳过；
- 全 Reactor PMD 3.28.0 / PMD 7.17.0：0 违规；
- Fulfillment SpotBugs 4.9.8 低阈值专项：29 条诊断，其中 Priority 1 为 0、
  Priority 2 为 21、Priority 3 为 8；
- GEO 新代码只剩 6 条 Spring 单例构造注入引用类诊断，没有空间计算、SQL、并发、
  空指针或资源泄漏类高优先级问题。

该数字只记录 M8.9 交付时点，不是当前仓库最终基线。2026-07-25 的 M8 整体收口
快照为 97 份 Surefire 报告、399 tests；2026-07-28 进入 M9 前复审后的当前基线
为 100 份报告、435 tests，0 失败/错误/跳过。

## 7. 真实 MySQL/Redis 验证

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
pwsh -NoProfile -File .\tools\verify-m8-fulfillment-geo.ps1
```

最终证据：

```text
backend/.run/m8-fulfillment-geo-20260724-205748/verification.json
```

真实脚本验证：

- 网络前置门禁、MySQL/Redis 容器和独立 Fulfillment JVM；
- V8/V9 Flyway 真实执行与 JAR 内迁移发现；
- 南京、上海和乱序苏州三条带坐标轨迹；
- 最新位置保持上海，乱序旧事件不覆盖；
- 顾客所有者读取成功，跨账户读取 404；
- `POINT SRID 4326`、空间索引和 `ST_Distance_Sphere` 附近查询；
- 事务提交后 Redis `GEOPOS` 与元数据写入；
- 删除 Redis Key 后回退 MySQL；
- `CLIENT PAUSE` 模拟 Redis 暂时不可用时回退 MySQL；
- 管理员从 MySQL 重建 Redis GEO；
- 最终数据库夹具、Redis Key、18106 监听和 JVM 残留均为 0。

首次真实运行还暴露并修复了 MySQL Connector/J 9.7 不支持
`ResultSet.getObject(..., Instant.class)` 的兼容问题，最终改为
`Timestamp.toInstant()`。这说明 H2 自动化不能替代真实 MySQL 驱动和空间类型验证。

## 8. 当前坐标

本批交付时 M8.1–M8.9 已完成，商品评价、搜索和运营统计仍待独立闭环。2026-07-25
当前状态以
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)
为准：后续三批及整体审查均已完成，M8 已关闭。2026-07-28 的工程证据复审也已
完成，但 M9 的范围与准入决定仍冻结，必须等待用户复审后单独确认进入。
