# M5 写链容量与 Catalog 多级缓存

> 日期：2026-07-21  
> 状态：M5 已完成；M0 至 M5 全量复验、静态门禁、真实中间件回归和基线移除已收口。详见 [M0–M5 全量回归与毕业收口](44-m0-m5-full-regression-20260721.md)

## 1. 写链正式容量矩阵

正式编排目录：

```text
backend/.run/m5-write-capacity-baseline-final-corrected-20260721-r6
```

固定参数：

| 项目 | 值 |
| --- | ---: |
| 每个 suite 请求数 | 1,000 |
| 重复次数 | 3 |
| 并发阶梯 | 1 / 5 / 10 / 20 / 50 / 100 |
| JVM 堆 | 256 MiB |
| 活动处理器 | 4 |
| Hikari 最大连接 | 20 |
| Tomcat 最大线程 | 100 |
| Trade 查询舱壁 | 128 |
| Trade 命令舱壁 | 128 |
| Trade→Marketing 试算/锁定舱壁 | 64 |
| Outbox 发布器/消费者 | 本矩阵关闭，避免后台任务污染容量事实 |

主矩阵包含购物车更新、结算试算、普通下单、支付创建准备、支付回调和混合读写。最终产生 108 个主结果及 correctness gates 结果，共 113 份 `result.json`；所有结果 `passed=true`，传输错误和响应契约错误均为 0。

correctness gates 通过：

- 同一订单幂等键并发 100 次只产生 1 个订单、1 个 Marketing 锁和 1 个 Inventory 预占；
- 同一支付回调并发 100 次只产生 1 笔支付、1 条回调、1 笔交易和 1 条 Outbox；
- 1,000 次库存竞争严格得到 100 笔预占、900 笔拒绝，预占数量、库存流水和 Outbox 数量一致；
- gates 中未发布 Outbox 均为 0。

### 1.1 全局净增量断言修正

第一次正式运行曾用全局 before/after `reserved`、Marketing lock 等净增量做跨域断言，误把旧的 `M5W` 订单在运行期间被 `OrderRecoveryJob` 取消、释放库存和权益的合法变化判成失败。

当前编排器改为：

1. 保存本批 Trade 订单的 `marketing_lock_no` 与 `reservation_no`；
2. 按这些精确外键读取 Marketing、Inventory 事实；
3. 只对本批订单断言数量、状态、数量和幂等关系；
4. 每个外层并发级别使用唯一的支付准备目录，避免 `result.json` 互相覆盖。

因此，M5 写链不再依赖容易被后台恢复任务污染的全局净增量。

## 2. Catalog 多级缓存正式门禁

正式编排目录：

```text
backend/.run/m5-catalog-cache-smoke-20260721-r9
```

两实例使用相同 `m5-cache-baseline` namespace，真实 MySQL、Redis、Nacos 和 Catalog jar 参与验证。正式门禁通过：

| 门禁 | 结果 |
| --- | --- |
| 1,000 次同 Key 热点请求 | 1,000/1,000 HTTP 200；MySQL 回源 1 次 |
| 1,000 次同 Key 空值穿透 | 1,000/1,000 HTTP 404；负缓存存在；MySQL 回源 1 次 |
| 逻辑过期与异步刷新 | 1,000/1,000 HTTP 200；stale response 计数增加 103 |
| 两实例 Redis Pub/Sub 失效 | Catalog-2 失效计数增加 1；共享 Key 删除后重新回源 |
| Redis 运行时停机 | Catalog 保持运行并从 MySQL 回源，HTTP 200 |
| Redis 恢复 | Redis healthy 后重新写入并读取缓存，HTTP 200 |
| 重建背压 | 100 个不同冷 Key 中 99 个明确返回 503、1 个返回 404；无 500/传输错误 |

实现边界：

- L1 使用 Caffeine，L2 使用 Redis，MySQL 是最终事实；
- 普通缓存档 `rebuild-wait=2s`，保证冷启动热点同键单飞不会被过短等待误拒绝；
- 背压门禁显式使用 `rebuild-max-concurrent=1`、`rebuild-wait=0ms`；
- Redis 停机验证保持业务 JVM 运行，先通过 Pub/Sub 清空本地缓存，再停止 Redis，避免把“业务先于 Redis 启动”混入运行时降级结论；
- 当前已知边界：Redis 停机时新启动 Catalog 的订阅容器无法完成初始连接，启动会失败；运行中的 Catalog Redis 断开可回源 MySQL。该边界保留在 M5 后续治理项，不伪装成已解决。

## 3. 为门禁发现并修复的代码问题

- Catalog `SecurityFilterChain` 补齐 `MetricsScrapeAuthenticationFilter` 和 `ROLE_METRICS` 的 Prometheus 权限；
- Catalog 增加 `micrometer-registry-prometheus`，使 `/actuator/prometheus` 真正存在并可被专用 token 抓取；
- M5 查询编排为 Catalog 设置独立 `APP_ENV`，避免缓存 namespace 与其他运行混用；
- 缓存门禁脚本修复 PowerShell scope 插值、Prometheus 数值捕获组和缓存失效等待；
- M5 写链编排修复批次级跨域事实、支付准备目录唯一性和受控 Trade 同步容量。

Catalog 模块测试与打包已通过：

```powershell
mvn -q -pl services/catalog-service -am test
mvn -q -pl services/catalog-service -am package -DskipTests
```

Node 负载执行器 4 个测试通过。

## 4. 中间件观察

写链证据完成后执行 M5 `Remove` 时，`plainjournal-mysql` 曾自动重启 1 次；`OOMKilled=false`，恢复后真实应用账号查询和 `Seed -> Verify` 均通过。重启前 MySQL 日志出现 100 MiB redo log 达到上限和 log writer 等待告警。没有在本批擅自修改 Docker 数据或全局 MySQL 配置；M0 至 M5 全量复验需要继续记录 redo log、容器重启和 InnoDB 状态。

Redis 故障门禁结束后，`plainjournal-redis` 为 healthy，Catalog 端口和 PlainJournal Java 进程均已释放，7 个核心中间件容器保持运行。

## 5. 全量收口结果

M5 基线已按 `prepare-m5-baseline-data.ps1 -Action Remove` 清理，Catalog、Identity、Trade、Inventory 的基线计数均为 0。最终 `mvn clean verify`、PMD、SpotBugs、前端 `pnpm check`、真实五中间件基础烟测、M3 多实例/发布治理、M4 权威结算/支付恢复/履约时间线及观测专项全部通过。

完整命令、结果和已识别的实验干扰源见 [M0–M5 全量回归与毕业收口](44-m0-m5-full-regression-20260721.md)。
