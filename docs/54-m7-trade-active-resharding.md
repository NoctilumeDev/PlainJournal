# M7 第六批：Trade 主动 2→4 重分片

> 验证日期：2026-07-23  
> 状态：机制实现、专项真实验证及 M0–M7 全面审查和回归均已完成；M8 准入  
> 范围：路由事实、断点批复制、在线变更追平、最终写栅栏、全列指纹、切换、受限回滚和四片读取

## 1. 结论

本批在 Trade 所有者域完成了受控的 `user_id % 2 -> user_id % 4` 主动重分片
代表闭环。源拓扑和目标拓扑都由 MySQL 保存最终业务事实；迁移按源分片分别执行
本地批事务，不引入 2PC、Redis 路由事实或跨 MySQL 大事务。

已经证明：

- 已提交批次中断后可以从 checkpoint 继续；
- 初始复制期间源两片可以继续产生新增、更新和删除；
- 最终写栅栏后能够重新追平全部用户，并删除目标孤儿行；
- 四个目标分片的用户聚合、Outbox、消费幂等、对账和秒杀请求与源事实一致；
- 人为篡改目标订单金额会阻止 Promote；
- 四片 Trade JVM 能按 `user_id % 4` 读取正确分片，跨用户读取返回 404；
- 确认目标未产生新写后可以清空目标并回到源两片，随后能够重新迁移。

这不是生产级无停机 CDC 平台。最终追平必须进入短维护写栅栏；没有建立目标到源的
反向复制，因此 Promote 后一旦目标接受新写，就不能直接使用本工具回滚。

## 2. 路由事实与迁移范围

源两片规则：

```text
source ds_0 <- user_id % 2 = 0
source ds_1 <- user_id % 2 = 1
```

目标四片规则：

```text
target ds_0 <- user_id % 4 = 0
target ds_1 <- user_id % 4 = 1
target ds_2 <- user_id % 4 = 2
target ds_3 <- user_id % 4 = 3
```

因此物理拆分为源 0 到目标 0/2、源 1 到目标 1/3。迁移覆盖 17 张按用户归属的表：

- 购物车锁、购物车项和游客合并请求；
- 订单、订单项、状态历史、地址/权益/价格/分摊快照；
- 售后订单、售后项和售后历史；
- 秒杀建单请求；
- Outbox、消费幂等和对账记录。

`consumer_failure` 是不带稳定用户所有者的控制事实，固定迁移到目标 `ds_0`。
`distributed_id_worker_lease` 不复制；源存在有效 worker 租约时禁止 Promote，
目标拓扑必须重新取得自己的租约。

## 3. 消费幂等所有者门禁

迁移 V15 为 `consumed_event` 增加 `owner_user_id`。Trade 的支付、履约、库存回补、
退款和售后事件消费在写入幂等事实时同步记录用户所有者，保证消费事实与业务副作用
迁往同一目标分片。

历史 `owner_user_id IS NULL` 不允许根据订单号或消息载荷猜测归属。初始化任务发现
任意 NULL 行时立即失败，必须先经过人工核对和治理。这条门禁避免把无法证明归属的
消费事实复制到错误分片后制造重复副作用。

## 4. 迁移状态机

工具：

```text
backend/tools/invoke-m7-trade-resharding.ps1
```

主要动作：

1. `Initialize`：校验两片源、四片目标、连续的 `ds_0...ds_3` 配置、NULL 所有者和
   活跃 worker 租约，写入 checkpoint。
2. `Copy`：按用户 ID 单调批复制，每个源分片独立提交；中断只丢失未提交批次。
3. `CatchUp`：在最终写栅栏前可重复追平；带 `-FinalWriteFence` 时重新扫描全部用户，
   同步新增和更新并删除目标孤儿行。
4. `Verify`：对四个目标分片的 17 张用户表及 `consumer_failure` 做行数与全列指纹
   核对，并确认非主目标片没有控制事实、目标没有 worker 租约。
5. `Promote`：只有最终写栅栏和完整校验均通过才写入 `PROMOTED`。
6. `Rollback`：必须显式提供 `-ConfirmNoTargetWrites`，只清空目标事实，不删除源事实。

checkpoint 只记录迁移控制状态，不代替源或目标 MySQL 的业务事实。重复执行已完成的
Copy 不会增加批次或重复业务行。

## 5. 受控四片 Profile

`application-m7-trade-resharding.yml` 是专用、互斥的四片 Profile。当前
`TradeShardingDataSourceConfig` 只接受严格连续命名的 2 片或 4 片配置，不泛化成
任意分片数。单机实验复用两个真实 MySQL 容器、四个 schema：

| 目标分片 | 物理 MySQL |
| --- | --- |
| `ds_0`、`ds_2` | `127.0.0.1:13306` |
| `ds_1`、`ds_3` | `127.0.0.1:13326` |

该布局证明路由、迁移和核对机制，不等价于四个独立 MySQL 节点，也不用于常驻开发。

## 6. 自动化门禁

执行：

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

- 27 份 Surefire 报告，109 tests，0 失败、0 错误、0 跳过；
- PMD：0 违规；
- SpotBugs：Priority 1 为 0、Priority 2 为 57、Priority 3 为 18；
- 两个新增 PowerShell 工具 Parser 错误为 0；
- 本批 `git diff --check` 通过，只有既有 LF/CRLF 提示。

## 7. 真实双 MySQL 验证

复现命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal
.\backend\tools\verify-m7-trade-resharding.ps1
```

最终证据：

```text
backend/.run/m7-trade-resharding-20260723-141747/verification.json
```

关键结果：

| 检查 | 结果 |
| --- | --- |
| 旧 NULL 所有者 | 初始化被正确阻止 |
| 提交后中断 | 源 0 提交 1 批，源 1 未推进 |
| 恢复初始复制 | 总批次从 1 收敛到 4 |
| 完成后重复 Copy | 批次数保持 4 |
| 在线变化 | 新增 1008/1009，更新 1000/1001，购物车删除/新增及消费事实新增均被追平 |
| 最终写栅栏 | 已启用并完成全用户追平 |
| 指纹 | 69 组全部一致，摘要 `24e4980984ac7ee2093a87279f5b65564c16f87283868733c463ab45c8c4c3b5` |
| 篡改门禁 | 修改目标 `ds_2` 订单金额后 Promote 被阻止 |
| 四片路由 | 用户 1000/1001/1002/1003 分别路由到 0/1/2/3 |
| 所有者隔离 | 跨用户订单读取 HTTP 404 |
| 回滚 | 状态 `ROLLED_BACK`，四个目标订单数均为 0 |
| 源事实 | 订单从 `4/4` 增至 `5/5`，迁移删除源事实数 0 |
| 回滚后重放 | 再次达到 `PROMOTED` |
| 清理 | 临时 schema、Java、端口和清理错误均为 0；第二分片恢复运行前状态 |

完整运行约 10.7 分钟。验证使用真实 MySQL 和真实四片 Trade JVM，但保持单机缩比，
没有同时常驻所有 M7 重型 Profile。

## 8. 边界与下一门禁

本批没有宣称：

- 最终写栅栏之外的生产无停机双写或 CDC 已完成；
- Promote 后目标产生新写仍可无损回滚；
- 四个 schema 等同于四个独立物理集群；
- 任意分片数、自动再均衡或控制面已经建立；
- 单独以 M7 专项通过替代 M0–M7 全面门禁。

M7 六批机制完成后，M0–M7 的源码、依赖、脚本、文档、自动化测试和真实关键闭环
已经完成全面审查与分批回归。最终门禁结论为 M8 准入；这不等于 M8 已实施，也不
把本机四 schema 实验外推为生产自动扩缩容。

相关基线：

- [项目总计划](00-project-master-plan.md)
- [Trade 服务](12-trade-service.md)
- [技术采纳矩阵与单机实验边界](17-technology-adoption-matrix.md)
- [M7 第四批：Trade 两分片代表实现](52-m7-trade-sharding.md)
- [M7 第五批：Trade 历史归档迁移、校验与回滚](53-m7-trade-history-archive-migration.md)
