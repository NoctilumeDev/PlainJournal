# M7 第四批：Trade 两分片代表实现

## 1. 目标与范围

本批只在 Trade 所有者域建立两分片代表实现，验证分片不是一份配置文件，而是
能够继续承载购物车、普通订单、支付推进、履约、整单售后、库存回补、退款、
Outbox、幂等消费和所有者域对账的完整业务机制。

本批没有把所有服务改造成分片拓扑，也没有引入 Redis 路由表、2PC 或跨片本地
事务。MySQL 继续保存业务事实和分片内幂等事实；Redis 不保存最终路由、订单或
库存裁决。

## 2. 拓扑与路由规则

Trade 使用 ShardingSphere-JDBC 5.5.3 和 Hint 数据库分片：

| 逻辑分片 | 物理位置 | 宿主机端口 |
| --- | --- | --- |
| `ds_0` | 核心 MySQL / `ecom_trade_shard_0` | `13306` |
| `ds_1` | `plainjournal-mysql-trade-shard-1` / `ecom_trade_shard_1` | `13326` |

路由规则固定为：

```text
shard_index = user_id % 2
```

`cart_item`、订单聚合、地址/价格/优惠快照、售后聚合、Outbox、
`consumed_event`、对账记录和秒杀请求等 Trade 表在两个物理库保持相同结构。
每个物理库独立执行 Flyway V1–V14，然后由 ShardingSphere 暴露一个逻辑
DataSource。

业务服务必须先建立 Hint，再创建事务和取得连接。相同调用栈可以嵌套使用同一
分片；尝试在一个本地事务或路由作用域中切换分片会失败关闭。购物车删除原先
使用方法级事务，事务可能早于 Hint 取得连接，本批已改为先路由、再使用显式
`TransactionTemplate`。

## 3. 查询、命令和控制数据边界

面向顾客的购物车、订单、游标分页、售后和取消命令都已持有 JWT `userId`，
因此直接进入单片，不执行广播写。

后台或内部接口只有订单号、售后号、秒杀请求令牌时，允许先执行受控只读广播
定位所属用户，再携带该 `userId` 进入单片事务。后台列表和对账问题查询允许
只读广播合并；任何状态迁移、恢复、取消、审核和消息副作用都必须在定位后的
单片内完成。该边界保持了既有 HTTP 契约，但也明确承认无分片键查询的成本，
不能把它扩展成任意后台分析查询。

跨服务事件中的 `PaymentSucceeded`、履约事件、退货验收、库存回补和退款结果
均携带 `userId`。Trade 先按该字段路由，再在同一物理库事务中写
`consumed_event` 和业务副作用，因此重复消息不会在另一片留下独立幂等事实。

无法从坏载荷可靠取得所有者的 `consumer_failure`，以及分布式 ID worker 租约，
属于控制数据，固定写入 `ds_0`。它们不参与用户聚合路由，也不能被误认为跨片
业务事务。

## 4. Outbox 与对账

Outbox 每次领取都逐片开启独立本地事务，领取结果记录物理分片编号。Broker
确认后，发布成功或失败更新回到原分片，不执行跨片更新。

初版逐片扫描始终从 `ds_0` 开始；当 `ds_0` 持续填满批次时，`ds_1` 可能长期
得不到领取。本批改为轮转首片：

```text
run 1: ds_0 -> ds_1
run 2: ds_1 -> ds_0
```

Trade 对账同样逐片开启本地事务，最后只合并数量结果。对账扫描不会跨片修复
业务数据，也不会把两个数据库包进一个事务。

## 5. 单机 Profile 与真实验证

Compose Profile 为 `m7-trade-sharding`，只增加一个 512 MiB 的第二 MySQL。
它与观测栈、Catalog 读副本和规模数据生成互斥。真实验证采用两阶段 JVM：

1. Identity、Catalog、Inventory、Marketing、Trade；
2. Inventory、Marketing、Trade、Payment、Fulfillment。

任一时刻最多五个业务 JVM，不常驻启动八服务双份或三份实例。

验证命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal
.\backend\tools\verify-m7-trade-sharding.ps1
```

脚本执行机器级网络预检，创建两个临时 Trade schema 和最小权限账号，启动
Profile 和阶段性 JVM，结束后删除探针、schema、账号、进程、端口和实验容器。

最终复跑前发现验证脚本复用了共享 Topic 和长期 Consumer Group：上一次 JVM
停机边缘未完成确认的 `ReturnStocked` 消息会在下一轮空分片库创建后重新投递，
形成跨轮次污染。最终脚本改为每轮创建四个独立 Topic 和十一个独立 Consumer
Group，并在 JVM 停止后删除 Group、Retry/DLQ 和 Topic。该修复没有绕开
RocketMQ，而是保证每轮真实消息证据相互隔离。

## 6. 自动化与静态门禁

最终代码状态执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -q -pl services/trade-service -am verify
mvn -q -pl services/trade-service -am `
  org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -q -pl services/trade-service -am `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  "-Dspotbugs.effort=Max" "-Dspotbugs.threshold=Low" `
  "-Dspotbugs.xmlOutput=true"
```

结果：

- 27 份 Surefire 报告，107 tests，0 失败、0 错误、0 跳过；
- PMD 7.17.0：公共模块和 Trade 均为 0 违规；
- SpotBugs：Trade Priority 1 为 0，Priority 2 为 51，Priority 3 为 17；
- 本批分片、数据源和 Outbox 相关类的 SpotBugs 告警为 0；
- 剩余两条 `CartService` Priority 2 是 Spring 注入对象的既有
  `EI_EXPOSE_REP2` 诊断，不为消除工具告警机械改造依赖注入结构；
- PowerShell Parser 和 `git diff --check` 通过。

双 H2 分片集成测试验证同片跨表事务、选定分片回滚和另一片不受影响；路由
单元测试验证确定性映射、Hint 清理、同片嵌套和跨片切换失败关闭。

## 7. 真实闭环结果

最终证据：

```text
backend/.run/m7-trade-sharding-20260722-200313/verification.json
```

真实 MySQL、Nacos 和 RocketMQ 链路已验证：

- 两片 Flyway V1–V14 完成；
- 奇偶用户分别进入 `ds_0` 和 `ds_1`；
- 订单、商品行、地址快照、价格快照、历史和 Outbox 与用户同片；
- 另一片不存在聚合泄漏；
- offset、keyset 游标和订单号点查均返回正确结果；
- `PaymentSucceeded` 的 `consumed_event` 与订单副作用同片；
- 履约创建、发货、轨迹、签收、整单售后、顾客寄回、仓库验收、库存回补、
  退款请求和签名回调完整收敛；
- 两片未发布 Outbox 均为 0；
- 两片 Trade 对账 `OPEN=0`；
- `distributed_id_worker_lease` 为 `ds_0=1, ds_1=0`；
- `consumer_failure` 为 `ds_0=0, ds_1=0`；
- 库存最终恢复为 `on_hand=10,reserved=0,available=10`。

独立清理复核不依赖脚本自报：两个实验 schema、两个实验账号、Identity/Catalog/
Inventory/Payment 探针、四个临时 Topic、十一个 Consumer Group、Retry/DLQ、
业务 JVM、端口 `18101–18107/13326` 和实验容器均为 0。

## 8. 当前边界与下一批

本批证明了两片下完整业务契约和最终一致性机制可以继续工作，但没有证明：

- 既有单表数据可以无停机迁移到两片；
- 分片扩容可以从 2 片在线变为更多片；
- 跨片后台查询已经适合大规模运营分析；
- 两片拓扑可以常驻在当前个人电脑。

第四批之后，第五批已经完成历史订单资格、逐片批迁移、断点续传、显式水位
刷新、11 表源目标指纹、切读门禁、回滚和回滚后重放，见
[M7 第五批：Trade 历史归档迁移、校验与回滚](53-m7-trade-history-archive-migration.md)。

因此 M7 第四批已完成，历史归档迁移缺口已由第五批关闭；后续第六批又完成受控
主动 2→4 重分片、最终写栅栏、四片指纹与读取、受限回滚和重放，见
[M7 第六批：Trade 主动 2→4 重分片](54-m7-trade-active-resharding.md)。
冷热归档与活动数据扩容仍是两个独立机制，不能互相冒充。
