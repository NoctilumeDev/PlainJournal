# M3 Trade Outbox 多实例抢占与租约

> 验证日期：2026-07-20  
> 范围：M3 第一批，只覆盖 Trade Outbox 发布任务；不等价于 M3 整体完成

## 1. 目标与边界

本批针对 M2 容量准入暴露的 Trade Outbox 长尾，先解决多实例发布正确性与任务领取机制：

- 1、2、3 个 Trade 发布实例共享同一真实 MySQL Outbox；
- 同一事件最多只有一个有效 owner，同一聚合的后继事件不能越过前驱；
- broker ACK 成功后才标记 `PUBLISHED`；
- 实例死亡后，过期租约可由其他实例回收；
- 旧 owner 在租约过期或被重新领取后不能再提交成功/失败状态；
- 瞬时数据库锁冲突和 RocketMQ 失败不伪造成功，由后续调度重试。

本批完成时尚未覆盖应用容器化、Gateway 负载均衡、消费者竞争、滚动升级和三个进程终止点。后续批次已经补齐 Trade 容器化、1/2/3 实例发现、真实容器内 RocketMQ 发布与优雅停止、消费者竞争、进程终止、滚动升级和双版本兼容；M3 现已完成。详见 [M3 Trade 容器多实例与优雅停机](28-m3-trade-container-multi-instance.md)、[M3 消费者竞争、进程终止与发布治理](29-m3-consumer-fault-and-release-governance.md)和 [M3 双版本兼容、滚动发布与容量复测](31-m3-dual-version-and-capacity.md)。

## 2. 实现

`outbox_event` 的 V10 迁移新增：

- `claim_owner`：当前发布者唯一标识；
- `claim_until`：明确租约截止时间；
- `(status, claim_until)`：过期租约扫描索引；
- 聚合类型、聚合 ID、版本、创建时间和事件 ID组成的顺序索引。

发布领取在一个 `READ_COMMITTED` 本地事务中完成：

1. 使用 `FOR UPDATE SKIP LOCKED` 选择有限批次的过期 claim ID，并按主键回收；
2. 使用 `FOR UPDATE SKIP LOCKED` 选择可发布事件；
3. `NOT EXISTS predecessor` 只允许同聚合最早的未发布事件进入候选集；
4. 在事务提交前写入 `PUBLISHING + claim_owner + claim_until`；
5. 不同实例跳过其他事务已经锁定的候选，避免重复扫描同一批任务。

成功和失败回写都要求：

```text
status = PUBLISHING
claim_owner = 当前发布者
claim_until > 当前时间
```

因此过期 owner 即使晚到也不能改写状态。RocketMQ ACK 成功但本地状态未能更新时，系统保留至少一次投递语义，由消费端幂等键和唯一约束吸收可能的重复消息。

真实三实例首轮曾复现“全范围过期租约 UPDATE 与领取事务发生 InnoDB 死锁”。实现已改为 `SELECT expired IDs FOR UPDATE SKIP LOCKED -> 主键回收`，最终正式轮日志中死锁为 0。极少数瞬时数据库冲突会增加 `ecommerce.outbox.claims{outcome="contended"}` 并等待下一调度周期，不再泄漏为未治理的调度器异常。

## 3. 自动化测试

Trade 定向构建：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/trade-service -am package
```

第二批收口后重跑结果：46 个 Trade 测试，0 失败、0 错误、0 跳过。覆盖：

- 三个 publisher 并发竞争；
- 每个事件只进入一次成功发送路径；
- 同聚合版本 `1 -> 2 -> 3`；
- 过期 owner 的成功/失败回写均被围栏拒绝；
- 死实例 claim 到期后由其他 publisher 回收；
- 瞬时数据库 claim 冲突被观测并留给下一调度周期重试。
- 嵌套异步发布异常保留最深层连接或协议根因。

H2 测试使用真实 Flyway V10 和 MyBatis SQL，不以纯 Mock 代替迁移、领取和围栏验证。

本批收口后再次执行全量门禁：

```powershell
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：11 个 Reactor 模块全部成功；40 份 Surefire 报告共 134 个测试，0 失败、0 错误、0 跳过；PMD 7.17.0 全 Reactor 0 违规。

## 4. 真实 MySQL、RocketMQ 证据

正式命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\verify-trade-outbox-multi-instance.ps1
```

脚本默认运行三组各 1000 条事件的等价性实验。每组包含 500 个聚合，每个聚合有版本 1、2 两条事件；使用真实 MySQL 8.4.10、RocketMQ 5.3.2 Proxy 和 JDK 17。脚本只启动 Trade 探测实例，关闭消费者、恢复任务、对账和 Nacos 发现，并在 `finally` 精确终止探测 PID、删除探测数据。

正式结果：

| Trade 实例 | 事件 | 耗时 | 吞吐 | 实例成功分布 |
| ---: | ---: | ---: | ---: | --- |
| 1 | 1000 | 4706.075 ms | 212.491 events/s | 1000 |
| 2 | 1000 | 4573.742 ms | 218.639 events/s | 452 / 548 |
| 3 | 1000 | 5106.408 ms | 195.832 events/s | 500 / 449 / 51 |

三组共同断言：

- `PUBLISHED = 1000`，`PENDING = 0`，`PUBLISHING = 0`；
- 发布失败、Outbox `attempts`、状态冲突和 claim contention 均为 0；
- 所有启动实例都实际发布过事件；
- 同聚合顺序违规为 0；
- 正式轮日志中的 InnoDB 死锁为 0。

三实例附加故障验证：人工插入一个 `PUBLISHING`、owner 已死亡、租约为 2 秒的事件，集群在 2063.321 ms 内回收并发布；`stale_recovered = 1`，状态冲突为 0。

机器证据保存在忽略文件：

`backend/.run/trade-outbox-multi-instance.json`

## 5. 结论

本批证明了多实例正确性，但没有证明线性扩容：

- 2 实例相对 1 实例吞吐提高约 2.89%；
- 3 实例相对 1 实例下降约 7.84%，相对 2 实例下降约 10.43%；
- 三实例任务分布明显不均衡，`SKIP LOCKED` 保证互斥和进度，不保证公平；
- 当前单机、单 MySQL、单 Broker、每实例 8 发布线程下，第三个实例增加了数据库、线程和 Broker 竞争。

因此当前缩比实验的性能默认值是 2 个 Trade 发布实例；3 个实例继续用于正确性、退出、滚动升级和故障恢复实验，不能用“实例更多”替代容量证据。

这组 1000 条实验是 Outbox 发布器专项，不是完整 1000 下单链路复跑。M2 的 1018.326 秒端到端长尾是否消除，必须等 Trade 容器化和真实三实例入口完成后，使用同一 `run-foundation-smoke.ps1 -EnableCapacityBaseline` 场景重新测量。

## 6. 下一批

1. Trade 容器化、非 root、健康探针、优雅停机、唯一 `publisher-id` 和 Nacos 1/2/3 实例发现已经完成。
2. 下一批注入事务提交后/发送前、broker ACK 后/标记前、消费事务后/ACK 前三个进程终止点。
3. 验证 Gateway 入口负载均衡、滚动升级、失败版本回滚、消费者竞争和在途请求收敛。
4. 在相同 1000 请求、100 并发业务场景下复测消息链收敛时间，再决定是否调整 batch、并行度、调度间隔或 Broker 资源。

当前租约时间使用同一宿主机 JVM 时钟，符合本阶段单机缩比边界。进入多机部署前必须改用数据库时间或建立明确的时钟偏差预算，不能把本机时钟一致性外推到生产集群。

## 7. 后续收口

Trade 容器、消费者竞争、进程终止、发布治理、Inventory 结果未知恢复、真实双版本兼容和最终容量复测均已于 2026-07-20 完成，M3 已关闭。最终结果见 [M3 双版本兼容、滚动发布与容量复测](31-m3-dual-version-and-capacity.md)。
