# M7 第二批：分布式 ID 与节点租约

> 验证日期：2026-07-22  
> 状态：第二批已完成；M7 六批及 M0–M7 最终回归门禁均已完成  
> 范围：公共 ID 生成器、Trade worker 分配与租约、普通/秒杀订单主键、三 JVM 真实 MySQL 验证
> 后续状态：Catalog 真实读副本、Trade 两分片、历史归档迁移和主动 2→4 重分片均已完成

## 1. 结论

M7 第二批已经完成一套代表性的分布式 ID 主线实现，并接入普通订单与秒杀订单共享的 `trade_order.id`。该方案不依赖 Redis 保存节点事实，也不使用 Redlock；worker 冲突由 Trade 自有 MySQL 表裁决，租约丢失、续租异常、时钟回拨和本地安全有效期耗尽时均失败关闭。

正式验证顺序启动三个 Trade JVM，worker 分别为 0、1、2，每实例生成 1,000 个 ID：

- 总 ID 3,000，唯一 ID 3,000，碰撞 0；
- 每个实例内部严格递增；
- 每个 ID 解码后的 worker 位均与当前实例一致；
- 第四个 JVM 复用 worker 0，因 MySQL 租约冲突以退出码 1 启动失败；
- 结束后四个端口全部释放，活动租约为 0，专项租约行清理后为 0。

本批只证明代表性 ID 机制与 Trade 订单主键接入，不代表读副本、分片、归档或在线迁移已经完成。因此 M7 不能关闭。后续 Catalog 真实 MySQL 读副本已在 [M7 第三批](51-m7-catalog-read-replica.md)完成。

## 2. 设计

### 2.1 ID 布局

公共 `DistributedIdGenerator` 使用正数 `long` 范围内的 Snowflake 风格布局：

```text
41 bit timestamp delta | 10 bit worker | 12 bit sequence
```

- epoch 默认为 `2026-01-01T00:00:00Z`；
- worker 范围为 0–1023；
- 单毫秒序列范围为 0–4095；
- 同一生成器内同步生成，批量结果保持严格递增；
- 时钟小于上次发号时间时立即抛错，不等待、不借用逻辑时间；
- 单毫秒序列耗尽时只有限等待时钟前进，超过 500 ms 失败关闭；
- 可解码 timestamp、worker 和 sequence，便于验证与故障定位。

这套 ID 只提供唯一性与单实例内单调性，不承诺跨 worker 的全局严格时间顺序。

### 2.2 worker 分配

Trade 的 worker 解析顺序为：

1. 显式 `TRADE_DISTRIBUTED_ID_WORKER_ID`；
2. 未显式配置时，由 `SERVICE_INSTANCE_ID` 确定性派生 10 位 worker；
3. 本地默认实例 `local` 使用 worker 0。

确定性派生兼容已有 M3 多实例脚本，但哈希不承担唯一性裁决。两个实例即使派生出相同 worker，仍只能有一个实例取得 MySQL 租约。

### 2.3 MySQL 租约与失败关闭

Flyway V14 创建 `distributed_id_worker_lease`，主键为：

```text
namespace + worker_id
```

租约拥有者使用随机 owner 标识。获取、续租、释放和过期接管都通过条件 SQL 完成：

- 未过期且 owner 不同：拒绝获取；
- owner 相同：允许续期；
- 已过期：允许新 owner 接管；
- 释放只删除 owner 完全匹配的行；
- 续租更新失败或数据库异常：本地立即失去所有权。

本地所有权判断还保留一个完整续租间隔作为安全窗。即使调度线程被长时间饿死，只要进入租约最后一个续租间隔，生成器就先于数据库租约到期停止发号，避免仅依赖下一次续租任务发现问题。

### 2.4 业务接入边界

本批接入：

- 普通订单 `trade_order.id`；
- 秒杀订单 `trade_order.id`；
- `orderNo = ORD + id`；
- `reservationNo = RSV + id`。

订单项、地址快照、价格快照、优惠分摊、历史等服务内部行 ID 暂时继续使用 MyBatis `IdWorker`。项目不为了形式统一机械替换全部内部主键；后续只有出现明确路由、迁移或跨库定位需求时才扩大接入范围。

验证端点：

```text
GET /api/v1/trade/status/distributed-id?count=1000
```

仅在 `m7-id-verification` Profile 下注册，不属于顾客、管理端或内部服务业务 API。

## 3. 自动化测试

最终针对性 `verify`：

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| `platform-common` | 14 | 0 | 0 | 0 |
| `trade-service` | 87 | 0 | 0 | 0 |
| 合计 | 101 | 0 | 0 | 0 |

其中分布式 ID 专项覆盖：

- 单毫秒批量唯一与严格递增；
- 不同 worker 集合不相交；
- 时钟回拨失败关闭；
- 所有权丢失后拒绝发号；
- worker、批量大小与配置边界校验；
- 显式 worker 优先和实例 ID 稳定派生；
- MySQL/H2 租约竞争、续租、错误 owner 释放、过期接管；
- 重复 worker 管理器启动失败；
- 数据库租约行丢失后的生成拒绝；
- 续租调度延迟时，本地安全窗提前停止发号；
- 普通订单和秒杀订单真实主键解码为当前 worker。

## 4. 真实验证

工具：

```text
backend/tools/verify-m7-distributed-id.ps1
```

正式参数：

```powershell
.\tools\verify-m7-distributed-id.ps1 -IdsPerInstance 1000 -StartupTimeoutSeconds 90
```

工具执行以下步骤：

1. 运行机器级网络预检，确认代理、Docker、容器出站、七个核心中间件和单一 IPv4 默认路由；
2. 打包当前 `platform-common` 与 `trade-service`；
3. 使用真实 `ecom_trade` MySQL，并确认 Flyway V14；
4. 顺序启动 18204、18214、18224 三个 Trade JVM；
5. 分别生成 1,000 个 ID，校验数量、严格递增、worker 位和全局唯一；
6. 在 18234 启动重复 worker 0，要求启动失败且日志出现租约冲突；
7. 精确终止本批 JVM，确认端口释放；
8. 等待数据库租约失效，确认活动租约为 0，再清理本专项过期行。

正式结果：

| 指标 | 结果 |
| --- | ---: |
| 实例 | 3 |
| 每实例 ID | 1,000 |
| 总 ID | 3,000 |
| 唯一 ID | 3,000 |
| 碰撞 | 0 |
| 重复 worker 启动 | 被拒绝，退出码 1 |
| 脚本生成与冲突验证耗时 | 57.641 s |
| 清理后活动租约 | 0 |
| 清理后专项租约行 | 0 |

证据：

```text
backend/.run/m7-distributed-id/verification.json
backend/.run/m7-distributed-id/m7-id-0.log
backend/.run/m7-distributed-id/m7-id-1.log
backend/.run/m7-distributed-id/m7-id-2.log
backend/.run/m7-distributed-id/m7-id-duplicate-worker-0.log
```

## 5. 质量门禁

- `mvn -pl platform-common,services/trade-service -am verify`：101 项通过；
- PMD 7.17.0：0 违规；
- SpotBugs 4.9.8：75 条 Priority 2/3 诊断，Priority 1 为 0，缺失分析类为 0；
- PowerShell Parser：通过；
- `git diff --check`：无空白错误。

SpotBugs 首轮曾因 reactor 解析不到尚未安装的 `platform-common` 新类而报告缺失分析类。安装当前 common artifact 后重新执行，最终缺失类为 0；首轮结果不作为通过证据。

## 6. 当前边界与下一批

本批没有证明：

- 所有服务和所有内部表都需要统一替换主键生成器；
- 跨 worker ID 具有全局严格递增顺序；
- 机器时钟无需 NTP 或宿主机时间治理；
- worker 租约可以替代订单幂等键、唯一约束或业务状态机；
- Catalog 读副本已经解决复制延迟与读己之写；
- Trade 两分片、归档和在线迁移已经完成。

上述 Catalog 读副本后续已按互斥 Profile 完成复制延迟、读己之写、主库回退和恢复验证；Trade 两分片也已完成相同业务契约下的真实正逆向闭环。第五批进一步完成历史归档的断点续传、数据指纹、切读门禁、回滚和重放。当前下一批只处理主动 2→4 重分片，仍不与观测栈或其他重型实验同时常驻。

相关基线：

- [项目总计划](00-project-master-plan.md)
- [Trade 服务](12-trade-service.md)
- [技术采纳矩阵与单机实验边界](17-technology-adoption-matrix.md)
- [M7 第一批：规模数据、查询基线与游标分页](49-m7-scale-data-and-cursor-pagination.md)
- [M7 第三批：Catalog 真实 MySQL 读副本](51-m7-catalog-read-replica.md)
- [M7 第四批：Trade 两分片代表实现](52-m7-trade-sharding.md)
- [M7 第五批：Trade 历史归档迁移、校验与回滚](53-m7-trade-history-archive-migration.md)
