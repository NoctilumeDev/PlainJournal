# M7 第一批：规模数据、查询基线与游标分页

> 验证日期：2026-07-22  
> 状态：第一批已完成；M7 六批及 M0–M7 最终回归门禁均已完成  
> 范围：Catalog 商品读取、Trade 用户订单读取、离线规模数据、索引与执行计划、API 负载及清理恢复

## 1. 结论

M7 第一批已经建立可分档、可清理、可重复验证的数据规模与查询治理基线。当前完成的是单主库下的规模数据、索引和分页机制，不代表读副本、分片、归档或在线迁移已经完成。

本批采用“用执行时间换常驻空间”的单机 Profile：

- 只运行七个核心中间件及 Gateway、Identity、Catalog、Trade 四个临时 JVM；
- 观测栈、MySQL 读副本和分片容器不得与本专项同时常驻；
- Small 必须真实执行，Medium 通过资源门禁后才执行，Formal 必须显式授权；
- 每个运行保存 SQL、执行计划、API 负载、JVM、容器和主机资源证据，结束后释放业务 JVM；
- 数据生成与查询完成后执行范围清理，不让专项数据污染后续业务验证。

## 2. 已落地能力

### 2.1 数据工具

`backend/tools/prepare-m7-scale-data.ps1` 提供 `Seed`、`Verify` 和 `Remove` 三种动作：

| Profile | SPU | SKU | 用户 | 订单 | 订单行 | 当前策略 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Small | 10,000 | 20,000 | 1,000 | 50,000 | 100,000 | 当前机器必跑 |
| Medium | 50,000 | 100,000 | 5,000 | 250,000 | 500,000 | 资源门禁后按需运行 |
| Formal | 100,000 | 200,000 | 10,000 | 1,000,000 | 2,000,000 | 显式 `-AllowFormal`，留给上限实验或更大环境 |

Small 数据生成耗时 23.412 秒。工具按保留 ID 区间写入 Catalog、Identity 和 Trade，逐项核对金额、订单子表、优惠分摊和稠密用户订单数量；重复 Seed 会先清理自己的数据区间。

### 2.2 查询与证据工具

`backend/tools/run-m7-scale-query-baseline.ps1` 对以下路径保存真实证据：

- 首屏 offset；
- 深页 offset；
- 深页 keyset；
- 商品 ID 和订单号点查；
- Gateway API 并发负载；
- `EXPLAIN ANALYZE` 与 JSON 执行计划；
- 索引快照、GC、JVM 进程、MySQL、Redis、容器和主机资源。

首次 V3 冒烟暴露出一个证据时序问题：脚本原先先采集执行计划、后启动 Catalog 应用 Flyway。该次冒烟中的旧索引计划因此只保留为诊断信息，不作为正式结论。

工具现已增加 Catalog 迁移预启动：先启动 Catalog 应用待执行 Flyway、确认健康并停止，再采集 SQL 与执行计划。加固后 40 请求、并发 5 的复验通过，摘要明确记录：

```text
catalogMigrationsAppliedBeforeSqlEvidence = true
```

### 2.3 API 与索引

保留已有 offset 契约，同时新增通用游标契约：

- `GET /api/v1/catalog/products/cursor`
- `GET /api/v1/trade/orders/cursor`
- `CursorPageResponse<T>`
- `KeysetCursor`

游标包含版本、`createdAt` 和 `id`，采用 URL-safe Base64 编码。Catalog 与 Trade 都按 `(created_at DESC, id DESC)` 稳定排序；坏游标返回明确业务错误。旧 offset 接口继续服务需要总数和页码跳转的场景。

Catalog Flyway：

- V2 增加 `(status, created_at, id)` 和 `(status, category_id, created_at, id)`；
- V3 删除会诱导优化器选择后再排序的旧 `idx_product_spu_public`。

真实 `flyway_schema_history` 已确认 V1、V2、V3 均成功。V3 后首屏、深 offset 和 keyset 均使用 `idx_product_spu_status_category_created_id` 覆盖索引，不再出现排序节点。

## 3. 正式 Small 结果

正式证据：

```text
backend/.run/m7-scale-data-small.json
backend/.run/m7-scale-query-small-post-v3
backend/.run/m7-scale-query-preflight-hardening-smoke
backend/.run/m7-scale-data-small-remove.json
```

V2 历史对照保留在：

```text
backend/.run/m7-scale-query-small-20260722-235158
```

### 3.1 SQL

| 查询 | V2 P50 | V3 最终 P50 | V3 计划 |
| --- | ---: | ---: | --- |
| Catalog 深 offset，跳过 7,900 行 | 3.026 ms | 1.296 ms | 覆盖索引反向扫描 8,000 行 |
| Catalog 深 keyset | 0.305 ms | 0.262 ms | 覆盖索引范围扫描 100 行 |
| Trade 深 offset，跳过 31,900 行 | 3.085 ms | 3.214 ms | `idx_trade_order_user_created` 扫描 32,000 行 |
| Trade 深 keyset | 0.265 ms | 0.223 ms | 范围扫描 100 行 |

V3 的核心收益是消除 Catalog 的旧索引误选和 filesort。单次毫秒数字会受缓存与主机调度影响，不能把不同运行间的小幅变化解释成生产容量提升；扫描行数和执行计划才是稳定证据。

### 3.2 Gateway API

默认参数为每个领域 300 请求、并发 20；四种场景各 75 请求：

| 场景 | P50 | P95 | 错误 |
| --- | ---: | ---: | ---: |
| Catalog 首屏 | 64.01 ms | 83.34 ms | 0 |
| Catalog 深 offset | 95.30 ms | 117.25 ms | 0 |
| Catalog keyset | 55.22 ms | 74.92 ms | 0 |
| Catalog 点查 | 26.94 ms | 61.16 ms | 0 |
| Trade 首屏 | 67.65 ms | 93.08 ms | 0 |
| Trade 深 offset | 157.34 ms | 190.72 ms | 0 |
| Trade keyset | 55.04 ms | 74.81 ms | 0 |
| Trade 点查 | 31.19 ms | 55.77 ms | 0 |

Catalog 与 Trade 合计 600 个请求全部成功。offset 与 keyset 各比较 100 条结果，顺序、首尾业务 ID 和数量完全一致，没有跨页重复或遗漏。

API 数字包含 Gateway、JWT、DTO 组装、订单子表批量加载和本机调度，不等同于纯 SQL 指标，也不外推为生产 SLO。

## 4. 资源与质量门禁

正式负载期间：

- 四个 JVM 均限制为 256 MiB 堆和 4 个有效处理器；
- 主机可用物理内存约从 4.22 GiB 降至 2.38 GiB；
- MySQL 容器内存接近当前 768 MiB 上限，但未重启、未 OOM；
- Redis `evicted_keys=0`；
- 运行结束后 Java 进程为 0，18000–18107 业务端口全部释放。

该结果说明当前机器可以稳定完成 Small，但不支持把读副本、两分片、观测栈和多实例同时常驻。后续继续采用互斥 Profile，而不是削弱 M0–M6 已有机制。

代码门禁：

- `platform-common` 9、Catalog 11、Trade 77，共 97 个测试通过；
- 0 失败、0 错误、0 跳过；
- PMD 7.17.0：0 违规；
- SpotBugs：79 条 Priority 2/3 诊断，Priority 1 为 0，缺失分析类为 0；
- 修改后的 PowerShell 工具通过 Parser，并完成真实低负载复验。

## 5. 清理结果

Small 专项数据清理耗时 17.227 秒。工具清单与独立 SQL 均确认：

- Catalog 分类、品牌、SPU、SKU 的 M7 保留区间为 0；
- Identity 用户、角色和 M7 邮箱前缀为 0；
- Trade 订单、订单行、地址、价格、优惠分摊和状态历史保留区间为 0；
- `missing_required_children` 与 `order_amount_mismatches` 为 0；
- MySQL 清理期间未重启、未 OOM。

Flyway V2/V3 和业务索引属于正式 schema 演进，清理数据时保留。

## 6. 当前边界

本批没有证明：

- Medium 或 Formal 数据规模可以在当前机器稳定运行；
- Catalog 读副本、复制延迟和读己之写；
- Trade 两分片下单、点查、分页、支付、履约和退款；
- 历史归档、在线迁移、断点续传、扩容与回滚；
- `%keyword%` 模糊搜索可以使用当前 B-Tree 分页索引；
- 当前单机延迟可以代表生产容量。

因此 M7 不能关闭。下一批优先完成分布式 ID 的多实例唯一性、节点冲突和时钟异常验证；随后按互斥 Profile 启动 Catalog 真实读副本，再进入 Trade 两分片、归档和在线迁移。

后续状态：第二至第五批已经依次完成分布式 ID、Catalog 真实读副本、Trade
两分片与历史归档迁移。当前只剩主动 2→4 重分片，不修改本批当时的数据与结论。

相关基线：

- [项目总计划](00-project-master-plan.md)
- [Catalog 服务](10-catalog-service.md)
- [Trade 服务](12-trade-service.md)
- [技术采纳矩阵与单机实验边界](17-technology-adoption-matrix.md)
- [M0–M6 全量回归与毕业收口](48-m0-m6-full-regression-20260722.md)
