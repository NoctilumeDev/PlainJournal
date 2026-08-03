# M7 第五批：Trade 历史归档迁移、校验与回滚

> 验证日期：2026-07-22  
> 状态：第五批已完成；其后第六批已关闭主动重分片缺口  
> 范围：历史订单资格、逐片批迁移、断点续传、水位刷新、数据指纹、切读门禁、回滚和真实双 MySQL 清理

## 1. 结论

本批完成了 Trade 历史订单冷热归档的最小真实闭环。归档不直接删除热库事实，
也不使用 Redis 保存游标、路由或最终状态；两个物理分片分别执行本地批事务，
归档控制表记录固定截止点、高水位、稳定主键游标、批次审计、源目标摘要和
切读状态。

最终真实验证证明：

- 批次提交后进程中断不会丢失已提交游标，重启后从原位置继续；
- 显式刷新高水位后可以纳入迁移期间新增的合格历史订单；
- 完成后重复执行不新增批次、不重复复制；
- 11 张订单聚合与 Outbox 表逐表计数和指纹一致；
- 人为篡改归档订单金额会阻止切读，修复后才能重新验证；
- 切读门禁只在两个分片均通过校验后激活；
- 回滚只删除本次归档副本和切读标记，不删除源订单；
- 回滚后可以使用同一任务重新初始化、迁移、校验和切读；
- 随机 schema、临时第二分片容器和所有实验资源均已清理。

这不是主动分片扩容。本批当时没有把 `user_id % 2` 的在线事实迁往
`user_id % 4`；该独立缺口随后由
[M7 第六批：Trade 主动 2→4 重分片](54-m7-trade-active-resharding.md)关闭。

## 2. 归档资格

归档任务固定 `cutoff_at`，只选择截止点之前更新的终态订单：

```text
COMPLETED
CANCELED
CLOSED
```

以下任一条件存在时拒绝归档：

- 售后仍为非终态；
- 订单或售后聚合仍存在未发布 Outbox；
- 订单号或售后号仍存在 `OPEN` 对账问题；
- 订单更新时间不早于固定截止点；
- 订单仍处于支付、取消、履约或异常处理中。

售后终态允许 `COMPLETED`、`REJECTED` 和 `CANCELED`。资格在源 Trade MySQL
上裁决，归档目标不能反向决定源订单是否可迁移。

## 3. 批迁移与断点

工具：

```text
backend/tools/invoke-m7-trade-archive-migration.ps1
```

每个物理分片拥有独立归档 schema 和控制表：

```text
trade_archive_job
trade_archive_batch
trade_archive_order_manifest
trade_archive_read_cutover
```

归档数据覆盖：

```text
trade_order
order_item
order_status_history
order_address_snapshot
order_benefit_selection
order_price_snapshot
order_discount_allocation
after_sale_order
after_sale_item
after_sale_history
outbox_event
```

每批按 `trade_order.id` 单调前进，在当前物理分片内执行一个本地事务：

1. 领取 `last_order_id < id <= high_watermark_id` 的合格订单；
2. 复制订单、快照、分摊、售后和已发布 Outbox；
3. 写订单 manifest 和批次审计；
4. 更新 `last_order_id` 与累计数量；
5. 一次提交本分片批次。

不存在跨两个 MySQL 的事务。一个分片提交成功、另一个尚未开始时可以安全停机；
恢复后分别读取自己的 checkpoint。

`-RefreshWatermark` 是显式操作。它在最终校验前重新计算固定截止点内的候选
高水位，适用于迁移期间补入的合格历史数据；正常新订单的更新时间晚于截止点，
不会被静默纳入同一任务。

## 4. 校验与切读

校验同时检查：

- 初始化候选数与当前候选数；
- manifest 订单数；
- 11 张表的源目标行数；
- 每行全部列的 SHA-256；
- 每张表四段 64 位摘要的聚合结果；
- 全聚合摘要。

主键和唯一约束阻止归档表出现重复事实，逐表计数与四段摘要共同用于切读门禁。
本方案不把简单行数相等当作数据一致。

`Promote` 只写 `trade_archive_read_cutover` 并把任务标为 `PROMOTED`。它是运维
切读门禁，不会在未实现归档查询契约时偷偷改变顾客 API，也不会删除源数据。

## 5. 回滚

`Rollback` 根据 manifest 精确删除当前任务写入的归档行，撤销切读标记，并把
任务置为 `ROLLED_BACK`。删除顺序覆盖 Outbox、售后子表、订单子表和订单主表。

回滚不执行：

- 删除或修改源 Trade 订单；
- 修改订单、售后、支付或履约状态；
- 跨分片大事务；
- 直接把校验失败任务改成成功；
- 删除不属于当前 `job_id` 的归档事实。

源事实保留使回滚后重放成为可执行恢复路径，而不是文档中的理论步骤。

## 6. 真实双 MySQL 验证

验证工具：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m7-trade-archive-migration.ps1
```

脚本读取本地网络基线并运行机器级预检，使用：

| 分片 | MySQL | 临时源/归档 |
| --- | --- | --- |
| 0 | `plainjournal-mysql` | 随机 source schema + 随机 archive schema |
| 1 | `plainjournal-mysql-trade-shard-1` | 随机 source schema + 随机 archive schema |

两个源 schema 均执行 Trade V1–V14 完整迁移。每片初始放入：

- 3 笔合格历史订单；
- 1 笔处理中订单；
- 1 笔截止点后的完成订单；
- 1 笔存在待发布 Outbox 的完成订单；
- 1 笔存在开放对账问题的完成订单；
- 1 笔存在非终态售后的完成订单。

验证结果：

| 门禁 | 结果 |
| --- | --- |
| 批次提交后中断 | `shard0=2/3`，`shard1=0/3`，checkpoint 保留 |
| 在线候选补入 | 每片增加 1 笔合格订单 |
| 刷新水位并续跑 | 两片均为 `4/4`、2 个批次 |
| 完成后重跑 | 新增批次 0 |
| 初次源目标校验 | 两片 11 张表全部匹配 |
| 人为修改归档金额 | shard 1 校验失败，切读被拒绝 |
| 修复后复验 | 两片重新匹配 |
| 切读门禁 | 两片均为 `PROMOTED` |
| 回滚 | 归档订单 0，源订单仍各 9 笔 |
| 回滚后重放 | 两片再次 `PROMOTED`、各 4 笔 |
| 清理 | 临时 source/archive schema 0，清理错误 0 |

最终证据：

```text
backend/.run/m7-trade-archive-20260722-205031/verification.json
```

第二分片容器在运行前不存在，验证结束后恢复为不存在；两个真实 MySQL 均无
残留随机 schema。

## 7. 质量门禁

最终执行：

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

- 27 份 Surefire 报告，107 个测试，0 失败、0 错误、0 跳过；
- PMD 7.17.0：0 违规；
- `platform-common + trade-service` SpotBugs：Priority 1 为 0、
  Priority 2 为 57、Priority 3 为 18；
- 两个 PowerShell 工具 Parser 错误为 0；
- 本批文件 `git diff --check` 通过。

本批没有修改 Java 业务代码，因此自动化数量与第四批 Trade 聚合门禁相同。
真实双 MySQL 脚本承担迁移、故障和回滚机制验证，不能由 H2 测试替代。

## 8. 当前边界与下一批

本批没有证明：

- 本批归档工具可以把当前两片在线扩为四片并切换新的路由规则；
- 活跃订单可以在双写或变更捕获期间安全重分片；
- 顾客历史订单 API 已自动从冷热两层合并读取；
- 归档副本已经迁移到独立存储集群或对象存储；
- 源热表可以在没有保留期、备份和审计批准时删除；
- 当前单机实验等同于生产级迁移平台。

后续第六批已完成主动 2→4 重分片的路由事实、批迁移、在线变化追平、最终写栅栏、
切换、受限回滚和跨版本数据核对。M0–M7 全面回归、死代码与文档治理也已完成，
当前结论为 M8 准入但尚未实施。

相关基线：

- [项目总计划](00-project-master-plan.md)
- [Trade 服务](12-trade-service.md)
- [技术采纳矩阵与单机实验边界](17-technology-adoption-matrix.md)
- [M7 第四批：Trade 两分片代表实现](52-m7-trade-sharding.md)
- [M7 第六批：Trade 主动 2→4 重分片](54-m7-trade-active-resharding.md)
