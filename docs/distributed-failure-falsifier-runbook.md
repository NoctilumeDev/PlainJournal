# 分布式故障反证协议

> 状态：`PARTIALLY EXECUTED / C1+C2 FIX CANDIDATE RERUN PASS`  
> 适用对象：PlainJournal 当前 Reference Baseline  
> 首轮批准源码：`f970d570c1b0fd398269927fec9cdfdc2f512690`  
> 当前结论：C1、C2 已分别复现真实首败并在未合并修复候选上重放通过；其余场景仍为
> `NOT_RUN`，当前结果不等于 exact-main 或发布资格

## 1. 目的与停止线

本协议不以“覆盖所有分布式故障”为目标。它只回答：PlainJournal 已经声明的关键不变量，
在时间、顺序、authority、资源和网络开始不可靠以后是否仍然成立。

执行顺序固定为：

```text
冻结源码、构件与运行坐标
        ↓
读取基线事实
        ↓
只改变一个变量
        ↓
保存首个失败和中间事实
        ↓
判定 PASS / FAIL / INCONCLUSIVE
        ↓
清理并读回
        ↓
只有真实 FAIL 才进入最小修复
```

出现以下任一条件即停止扩展：

- 已获得时间失权、世代失权、事实源失权、重复/乱序和恢复边界的代表性样本，继续执行不再产生新的 failure shape；
- 宿主资源、Docker/WSL 或测试工具改变了被测拓扑，无法再把结果归因给单一变量；
- 场景需要新增超出 Reference Baseline 的业务承诺；
- 清理无法证明只触及本轮拥有的用户、订单、消息、缓存键、索引、容器、进程和端口。

本轮不新增大型业务域，不把 PlainJournal Pro 的多机、跨地域或生产治理范围倒灌进
Reference Baseline，也不为了填满清单遍历每一个消费者、定时任务和更新语句。

## 2. 裁决语言

### 2.1 十个观察维度

每个场景只围绕以下维度记录事实：

```text
Actor       谁在执行
State       它开始时观察到什么状态
Resource    它使用什么有限资源
Time        authority 在什么时间窗口有效
Order       事件、回调和提交以什么顺序发生
Authority   它现在是否仍有资格读、写或完成
Identity    请求、消息、owner、版本和构件是谁
Failure     哪个边界发生了丢失、延迟、重复或中断
Recovery    恢复是否创造第二次副作用
Observation 哪个事实源支持结论
```

### 2.2 结果类别

| 结果 | 含义 |
| --- | --- |
| `PASS` | 反例已在批准坐标上实际执行，禁止结果未出现，业务事实和清理均成立 |
| `FAIL` | 反例实际打穿声明的不变量；保留首败，进入单独修复闭环 |
| `INCONCLUSIVE` | harness、宿主资源、Docker、网络或证据缺口使因果链无法闭合 |
| `NOT_RUN` | 只有协议或脚本，尚未执行 |

后来的 PASS 不删除较早的 FAIL；修复后应并列记录“原反例”和“修复后拒绝同一反例”。

## 3. 不可破坏的不变量

1. 同一用户下，`idempotencyKey + request fingerprint` 对同一业务意图至多产生一次有效副作用。
2. 订单、支付、退款、库存和履约状态只能在当前前提仍成立时沿合法状态机转换；旧结果不能强写当前事实。
3. 同一支付或退款逻辑操作不能因超时、重试、重复消息或恢复再次生效。
4. 库存不能为负；预占、确认、释放、退货和流水必须可按 owner schema 对账。
5. 旧事件、旧 lease owner、旧 loader 和旧版本实例不能覆盖更新的事实或恢复已经终止的状态。
6. 明确要求 primary/read-your-writes 的请求不能被不能证明新鲜度的较弱缓存或副本回答。
7. `UNKNOWN` 不能伪装成失败或成功；恢复必须先查询已有事实，再决定是否以同一身份重试。
8. graceful stop 与 crash 是两种实验；任何一方的结果都不能替代另一方。

## 4. 证据身份与运行纪律

每次正式执行必须记录：

- 40 位 Git SHA、`git status --porcelain` 和受保护源码指纹；
- 实际启动的 JAR/前端构件 SHA-256，以及本次构建如何绑定到该源码；
- Java、Node、Docker Desktop、Docker Engine、Compose、Windows/WSL 版本；
- 容器、JVM、端口、网络、数据库 schema、Redis key prefix、MQ topic/consumer group 和索引坐标；
- 介入前事实、唯一介入动作、介入后 owner DB/缓存/MQ/索引事实和清理读回；
- 浏览器场景的 route、登录世代、Network 结果和最终页面状态。

只把 Git SHA 写进报告，不等于证明实际 JAR 来自该 SHA。复用构件前必须单独证明
source -> build -> artifact -> process 连续性；无法证明时重新构建。

正式场景串行执行。不得为了节省时间并行启动两个完整 Python/Chromium/Compose harness，
因为额外执行者会改变内存、端口、线程和调度拓扑。

## 5. 宿主和故障注入边界

- 外部 GitHub 访问只允许在单条命令上显式使用已批准代理；不修改全局 Git、系统代理或 DNS。
- 故障代理必须限定到本轮容器网络和单一依赖边；不得修改宿主防火墙、hosts、DNS 或全局路由。
- Docker 首次只做正常启动。若再次出现 AF_UNIX/reparse-point 启动错误，保留首败并停止；
  不强删 socket、不 factory reset、不同时升级版本和改运行目录。
- 禁止 `docker compose down -v`、通配符删除卷、覆盖持久化目录和不带所有权证明的批量 kill。
- JDB/断点只允许暂停已经读完旧事实、尚未写 cache 的目标线程；不得改变量或用数据库锁重塑事务拓扑。
- 所有 timeout、retry、TTL 和 lease 配置必须在场景结束后恢复并读回；临时配置不得污染其他验收者。

运行前先执行仓库宿主预检：

```powershell
cd backend
./tools/check-verification-host.ps1 -SkipDocker
```

Docker 启动后再执行 Docker 预检。任一资源停止线触发时结果为
`INCONCLUSIVE / HOST OR HARNESS BOUNDARY`，不能调低断言换取绿色。

## 6. 有限攻击矩阵

| ID | 反例与目标不变量 | 当前入口 | 唯一变量 | 必须保留的事实 | 当前准备度 |
| --- | --- | --- | --- | --- | --- |
| C1 | Catalog 旧 loader 在 invalidate 后复活旧缓存 | 真实 Catalog JAR + 精确 JDB 暂停点 | 只暂停旧 loader 的 cache write | DB 新值、失效后空 key、旧线程恢复、Redis/API 旧值是否复活 | `FAIL REPRODUCED / FIX CANDIDATE RERUN PASS` |
| C2 | `primary` 强读被旧缓存提前回答 | Catalog API、MySQL、Redis、浏览器 Network | 只增加 `X-Catalog-Read-Consistency: primary` | 普通读、强读、DB primary、Redis envelope 和实际 datasource 访问 | `FAIL REPRODUCED / FIX CANDIDATE RERUN PASS` |
| T1 | 服务端成功但响应丢失，恢复不得产生第二次库存副作用 | `run-foundation-smoke.ps1 -EnableInventoryReservationResponseLossFaultInjection` | 只丢失一次已获上游 200 的响应 | request identity、订单、预占、流水、UNKNOWN 查询、取消后库存基线 | `EXISTING HARNESS / NOT_RUN` |
| M1 | 重复与乱序不能让旧 Catalog 搜索投影覆盖新 revision | Catalog search runner + 受控消息顺序 | 同 product 先交付 N+1，再交付 N，并重复 N | Outbox identity、revision、OpenSearch version、MySQL 当前状态、公开搜索结果 | `INJECTOR REQUIRED / NOT_RUN` |
| L1 | lease 过期后的旧执行者不能提交领域副作用 | `ConsumerFailureRetryCoordinator` 代表性 handler | A claim 后暂停，lease 到期由 B 接管，再恢复 A | claim owner、DB clock、handler 幂等事实、领域副作用、最终 retry 状态 | `TARGET SELECTION GATE / NOT_RUN` |
| S1 | graceful stop 与 crash 下的 in-flight work 不丢失也不重复 | Trade 多实例 runner；另补 crash 子场景 | 分别只做 `docker stop` 或 `docker kill` | deregistration、exit、Outbox/MQ/DB、副作用次数、接管与重启后事实 | `PARTIAL HARNESS / NOT_RUN` |
| B1 | 慢依赖不能通过多层重试放大成全站资源雪崩 | 容器网络内单边延迟代理 | 只给一个下游增加延迟，不断开 | deadline、attempt、线程/连接池、熔断、队列、其他链路可用性和恢复 | `HARNESS REQUIRED / NOT_RUN` |
| V1 | 新旧版本共存不能误读事件/状态或做不安全回滚 | gateway rolling + trade dual-version runners | 只改变一个服务版本 | source/artifact identity、Nacos 实例、路由、信封兼容、数据库迁移和回滚限制 | `EXISTING HARNESS / NOT_RUN` |

`EXISTING HARNESS` 只表示代码入口存在，不表示它仍适用于当前 SHA，更不表示曾经的 PASS
可以直接继承。`TARGET SELECTION GATE` 表示先审 concrete handler 的事务、幂等和副作用；
若没有一个代表 actor 能提供新的 failure shape，就关闭 L1，不为了完成表格硬造场景。

## 7. 场景裁决

### 7.1 C1：旧 cache writer

确定性交错必须是：

```text
旧 loader 完成 OLD 读取
        ↓ 暂停在 cache write 前
管理写提交 NEW，并完成 cache invalidation
        ↓
恢复同一个旧 loader
        ↓
检查 OLD 是否重新进入 Redis/local cache 并被真实 API 返回
```

数据库锁、延长 timeout 或另起第二个 loader 不能替代上述交错。允许结果是旧 loader
失去写资格或其结果不能被后续请求观察；若 `DB=NEW` 且 Redis/API 恢复为 `OLD`，记
`FAIL / STALE CACHE WRITE-BACK AUTHORITY`。

### 7.2 C2：primary 读 authority

C2 与 C1 分开裁决。C1 问“谁还能写缓存”；C2 问“谁有资格回答强读”。先形成一个可证明
来源的旧 cache envelope，再对同一商品分别执行普通读和带 `primary` header 的读。
若强读未访问 primary，或返回早于当前 primary 的状态，记
`FAIL / PRIMARY READ AUTHORITY INTERCEPTED`。不能用“最终 TTL 到期后变新”洗掉首败。

### 7.3 T1：timeout-but-success

代理必须证明上游已返回成功、客户端响应被有意丢弃。客户端只能把结果记为 UNKNOWN，
随后用原业务身份查询；只有确认服务端没有既有事实时才可重试。最终断言包含订单、库存
预占、库存流水和取消清理，不能只看 HTTP 状态。

### 7.4 M1：重复与乱序

同一 product 的 N+1 必须先被索引观察，再交付 N 和 N 的重复副本。允许旧事件保留为
历史输入，但不得使索引 revision 或公开商品状态回退。`external_gte` 的单元/集成测试只是
先验，不替代真实 OpenSearch 读回。

### 7.5 L1：zombie owner

最终 retry 记录按 owner 更新为 0 行，只能证明旧 owner 没有资格完成台账；它不能自动
证明 `handler.retry()` 在 lease 丢失以后没有产生领域副作用。正式选定目标前必须回答：

1. handler 的事务边界在哪里；
2. 副作用是否以 message/business identity 幂等；
3. A 暂停期间 B 是否能完成同一逻辑动作；
4. A 恢复后是否可能再次提交一次有效领域事实。

若具体 handler 已由更强的数据库唯一约束或状态前提完全拒绝旧动作，记录正例并停止；
不升级成全平台 fencing 设计。

### 7.6 S1：停止与恢复

`docker stop --time 30` 验证 graceful contract；`docker kill` 验证 crash recovery。两者分开
运行、分开取证、每次从同一批准基线开始。检查 HTTP 停止接入、Nacos 注销、Outbox drain、
Broker ACK/重投、接管实例和重启后 owner DB；不能只以容器重新变 healthy 判 PASS。

### 7.7 B1：慢依赖与重试放大

只在容器网络内给一个明确的 HTTP 或 Redis 边界增加固定延迟。先记录无故障 baseline，
再记录 client timeout、retry、circuit breaker、线程池、连接池和宿主资源。若多个层级重试
导致请求数指数放大、无关链路饥饿或恢复时 herd，保存首败；不同时改 timeout、pool 和 retry。

### 7.8 V1：版本共存

只选择仓库已经声明兼容或明确声明不兼容的一条边界。旧、新 JAR 必须分别绑定源码和哈希。
已知不允许混跑的 consumer 不能被重新包装成“测试失败”；该场景应验证发布限制确实会
阻止不安全共存，同时验证获准的 HTTP/信封/迁移路径。

## 8. 执行批次

1. **批次 A：高信息量缺口**——先运行 C1、C2；任一 FAIL 先独立修复和重放原反例。
2. **批次 B：现有防线复核**——串行运行 T1、M1、L1；无新 failure shape 时不横向扩到所有服务。
3. **批次 C：生命周期与宿主边界**——S1、B1；资源拓扑失真立即停止。
4. **批次 D：版本共存**——V1 最后执行，避免把部署拓扑变化污染前面场景。

每个真实缺口使用单独提交/PR。C1 与 C2 可以共享设计讨论，但必须保留两个独立反例和
两个裁决；不能因为一个修复顺带让另一个变绿，就省略它自己的合同证据。

## 9. 修复与功能边界

只有当前协议中的真实 FAIL 才授权 correctness 修复。修复前先确定 owner、状态前提和
最小 authority seam；不要顺手重写交易、支付、库存或会话协议。

代表性攻击闭环后，产品功能按以下顺序另开工作：

1. Catalog 商品经营后台；
2. `REAUTH_REQUIRED` 的用户承接；
3. 恢复/对账控制台。

这些功能不属于本反证协议的 PASS 条件。运行恢复控制台只允许调用领域 owner 提供的
受权命令，不建立跨库写事实的中央万能后台。

## 10. 当前执行结论

- C1 首败已在基线构件上证明：旧 loader 读完 `OLD + ACTIVE` 后暂停，管理写提交
  `NEW + INACTIVE` 并完成失效，旧 loader 恢复后把 OLD 重写入 Redis，后续真实 API 读到 OLD。
- C1 修复候选以固定数量的本地 authority stripe 把“观察 generation、缓存安装、失效撤权”
  排成可判定顺序。原在途请求仍可返回它已观察到的 OLD，但该观察不能再写入 local/Redis；
  随后的真实 API 读取当前 DB 并返回 404。
- C2 首败已在基线构件上证明：旧缓存存在且主库为 `NEW + INACTIVE` 时，带 `primary`
  header 的请求仍由旧缓存返回 200。
- C2 修复候选把当前 primary requirement 作为应用端口注入 Catalog service；明确强读绕过
  无法证明新鲜度的两级缓存并在主库只读事务中加载。重放时普通读仍返回 OLD 200，强读
  独立返回 404，证明旧缓存前提真实存在且没有被测试预先清除。
- 既有 response-loss、rolling、dual-version 和 graceful-stop runner 是可复用资产，不是
  当前运行结果。
- 历史 JDB、旧 JAR 或先前发布证据只作为设计输入；本轮不得把它们改写成当前 SHA 的 PASS。

修复候选 JAR 为 `SHA-256 F47FC9FD47EECF59973A4974EBF3C797A4F1723542571D98D5611A2DFF172A0A`。
它只证明当前工作树候选在本轮真实重放中拒绝了两个原反例；合并、exact-main CI 和安装后
读回完成前，不得把它升级为主线资格。
