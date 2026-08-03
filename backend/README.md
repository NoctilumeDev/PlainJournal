# Backend Foundation and Transaction Core

This directory contains the Spring Boot 3 multi-module backend. The eleven
applications now cover the complete self-operated B2C forward and whole-order
return/refund paths plus the M2 observability, resilience, compensation, tracing,
and owner-domain reconciliation baseline. M3 adds representative three-instance
deployment and failure evidence without changing these ownership boundaries. M6
adds a separate flash-sale admission, Outbox queue, idempotent order creation,
MySQL inventory verdict, result query, recovery, and mixed-traffic capacity path.
M7 now adds repeatable scale-data profiles, Catalog/Trade keyset pagination,
distributed IDs, a real Catalog MySQL read-replica experiment, a
ShardingSphere-JDBC Trade two-shard representative implementation, and a
checkpointed historical-order archive migration. It also includes controlled
active `user_id % 2 -> user_id % 4` resharding with owner routing facts,
checkpointed copy, final catch-up, full-column fingerprints, cutover gating,
four-shard read verification, restricted rollback, and replay. The six M7
mechanism slices and the project-wide M0–M7 audit/regression are complete.
M8.1 adds reliable MySQL persistence, transactional Outbox, client retry
idempotency, support assignment, history pagination, and read receipts. M8.2
adds Outbox leases, RocketMQ dispatch, Redis presence and node routes, JWT
WebSocket delivery, cross-node targeting, node-expiry fallback, and MySQL
offline replay. M8.3 adds private MinIO attachment upload intents, type and
SHA-256 confirmation, atomic message binding, authorized download, tamper
detection, and retryable orphan cleanup. M8.4 adds short-lived opaque browser
handshake tickets, Redis digest keys, atomic cross-node single use, expiry and
fail-closed Redis handling while preserving Authorization-header clients. M8.5
adds durable consumer-failure facts, post-persistence ACK, MySQL lease retries,
terminal attention semantics, recovery observation, and raw-payload-safe
Actuator/Prometheus views. M8.6 adds
customer and support-agent text workspaces, response-drop recovery, explicit
browser Origin policy, short-ticket WebSocket lifecycle management, and a real
two-browser verification gate. M8.7 adds quarantine-scoped object keys, real
ClamAV streaming scans, scan leases, finite retries, terminal attention state,
  and authorized idempotent audit recovery. M8.8 adds Notification-owned in-app
  facts, email preferences, Payment/Fulfillment event consumption, database
  leases, finite SMTP retries, stable message IDs, durable poison-event facts,
  and authorized idempotent audit recovery. M8.9 adds Fulfillment-owned MySQL
  latest-position facts, real MySQL spatial nearby queries, a rebuildable Redis
  GEO projection, cache fallback/read repair, and customer/admin coordinate
  views. M8.10 adds immutable-order-snapshot review eligibility, concurrent
  idempotent reviews, public summaries, likes, replies, reports, moderation, and
  consumer-failure governance. M8.11 adds a disposable OpenSearch product
  projection, local search Outbox, explicit fallback, audited recovery,
  blue-green rebuilds, reconciliation, and storefront search integration.
  M8.12 adds an Analytics-owned event log, daily and product projections,
  bounded reconciliation, audited idempotent rebuilds, dedicated metrics, and
  an operations dashboard without cross-service production-table joins. The
  twelve M8 mechanism slices and the project-wide M8 audit/regression are
  complete. M9 is admitted with a strict three-merchant and one Go statistics
  service boundary.

## Modules

```text
backend/
├── platform-common/              # Technical API contracts only
├── ecommerce-gateway/            # WebFlux routing, rate limiting, request tracing
└── services/
    ├── identity-service/         # Account, addresses, JWT, refresh token, RBAC
    ├── catalog-service/          # Product facts, media, reviews, search projection
    ├── inventory-service/        # Warehouse, stock, reservation, ledger, Outbox
    ├── trade-service/            # Cart, order snapshots, recovery, event consumers
    ├── payment-service/          # Payment order, signed callbacks, transaction, Outbox
    ├── fulfillment-service/      # Shipping, append-only logistics, MySQL spatial GEO
    ├── marketing-service/        # Benefits, pricing locks, regional rules, allocation
    ├── chat-service/             # Conversation, stored messages, idempotency, receipts
    ├── notification-service/     # In-app notifications and reliable email delivery
    └── analytics-service/        # Event-owned operational statistics and rebuilds
```

`platform-common` must not contain database entities or domain rules. Services
communicate through API/event contracts and never share mapper classes.

## Build

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

2026-07-30 最近一次全量 `mvn clean verify` 通过 100 份 Surefire 报告、435 个测试并打包全部应用；其中 `platform-common` 21 tests、`catalog-service` 44 tests、`trade-service` 121 tests、`chat-service` 59 tests 与 `analytics-service` 7 tests。独立 PMD 产生 12 份报告、0 违规；使用当前已安装的 Reactor 产物重跑 SpotBugs 低阈值扫描产生 12 份报告、313 条分类诊断，其中 Priority 1 为 0、Priority 2 为 247、Priority 3 为 66、缺失分析类为 0。服务测试使用 H2 MySQL compatibility mode；PMD 与 SpotBugs 没有绑定到 `verify`，因此需要单独执行。真实 MySQL、Redis、Nacos、RocketMQ、MinIO、ClamAV、SMTP、MySQL Spatial、OpenSearch、故障注入和容量验证使用本页后续的冒烟脚本，不能由 H2 回归结果替代。最终 Broker 清理门禁同时检查订阅组配置、`consumerOffset.json` 和 `%RETRY%/%DLQ%` Topic，不能再仅凭 `getConsumerConfig` 判定零残留。最终 M0–M8 三层证据见 `../docs/69-m0-m8-pre-m9-three-layer-audit-20260728.md`，最新订单/Payment 浏览器证据见 `../docs/77-frontend-order-payment-layering-seventh-slice-20260730.md`。

M8.1–M8.12 针对性门禁：

```powershell
mvn -pl services/chat-service -am test
./tools/verify-m8-chat-persistence.ps1
./tools/verify-m8-chat-realtime.ps1
./tools/verify-m8-chat-attachments.ps1
./tools/verify-m8-chat-browser-ticket.ps1 -SkipPackage
./tools/verify-m8-chat-consumer-failures.ps1 -SkipPackage
./tools/verify-m8-chat-frontend-workspace.ps1 -SkipPackage -SkipFrontendBuild
mvn -pl services/notification-service -am test
./tools/verify-m8-notification-delivery.ps1 -SkipBuild
mvn -pl services/fulfillment-service -am test
./tools/verify-m8-fulfillment-geo.ps1
mvn -pl services/catalog-service -am test
./tools/verify-m8-product-reviews.ps1 -SkipBuild
./tools/verify-m8-catalog-search.ps1 -EvidenceDate 20260724
mvn -pl services/trade-service,services/analytics-service -am test
./tools/verify-m8-analytics.ps1 -EvidenceDate 20260724
```

自动化覆盖同键顺序重试、8 路并发会话重试、16 路并发消息重试、客服认领、
越权拒绝、历史分页、Outbox 租约、Broker 成功/失败、实时回执、已读收敛，
以及 `RETRYING / NEEDS_ATTENTION / RECOVERED` 消费失败状态转换。
M8.1 真实脚本只启动 Chat 与 Gateway；M8.2 真实脚本启动两个 Chat 实例和 Gateway，
经 Nacos 路由并使用真实 MySQL、Redis、RocketMQ 核对跨节点投递、节点退出和离线
回放。M8.3 额外使用真实 MinIO 验证上传、完整性、授权下载、覆盖拦截和孤儿清理。
M8.4 使用真实 Gateway、两个健康 Chat 实例和一个 Redis 故障实例验证短期票据的
跨实例单次消费、重放/过期拒绝、Header JWT 兼容和失败关闭。M8.5 使用真实 MySQL、
Redis 与 RocketMQ 验证毒消息持久化后 ACK、临时故障建立
`RETRYING + next_attempt_at` 后 ACK 原消息、MySQL 租约有限重试恢复、
原始载荷隐藏和最终零残留。两次 360 秒失败基线已经证明不能把 Broker POP revive
当作唯一恢复保障。M8.6 使用真实 Identity、Chat、Gateway、MySQL、Redis、
Nacos、RocketMQ 和两个浏览器工作区验证创建/发送响应丢失恢复、认领前正文隔离、
无需刷新实时回复、刷新历史恢复和固定验证 consumer group。M8.7 复用附件脚本并
额外按需启动 ClamAV，验证隔离前缀、真实 EICAR、扫描器停机、有限重试、管理员
幂等审计重扫和恢复后绑定。M8.8 使用真实 Gateway、MySQL、Nacos、RocketMQ 和
本地 SMTP 捕获器，验证重复事件收敛、站内信不受 SMTP 故障影响、两次有限重试、
顾客越权拒绝、管理员幂等审计恢复、稳定 `Message-ID`、毒消息治理和原始载荷隐藏。
M8.9 使用真实 MySQL 8.4、Redis 和独立 Fulfillment JVM 验证 `POINT SRID 4326`、
空间索引、附近查询、乱序轨迹裁决、缓存缺失/暂停回退、读修复、管理员重建和顾客
所有权隔离。M8.10 使用真实 MySQL、Nacos、RocketMQ、Gateway、Trade 和 Catalog
验证订单完成快照事件、评价资格、并发幂等、举报审核和 Broker 停机恢复。M8.11 使用
真实 MySQL、按需 OpenSearch 和单个 Catalog JVM 验证增量投影、索引停机后的明确
MySQL 降级、三次有限重试、`NEEDS_ATTENTION`、幂等审计恢复、蓝绿重建、
`MISSING/STALE/ORPHAN` 对账修复和下架隔离。M8.12 使用真实 MySQL、Nacos、
RocketMQ、Gateway 和单个 Analytics JVM 验证重复事件收敛、Broker 保留与恢复、
旧事件收入覆盖边界、三类投影偏差、幂等审计重建、专用 Prometheus 身份和最终
零残留。各入口脚本都在结束时清理各自业务行、
失败/审计台账、对象/Redis Key、按需容器、索引、schema、授权、端口和进程。证据分别见
`../docs/56-m8-chat-reliable-persistence.md`、
`../docs/57-m8-chat-realtime-routing.md`、
`../docs/58-m8-chat-attachment-storage-and-authorization.md`、
`../docs/59-m8-chat-browser-websocket-ticket.md` 与
`../docs/60-m8-chat-consumer-failure-governance.md`、
`../docs/61-m8-chat-frontend-workspace.md`、
`../docs/62-m8-chat-malware-scan-and-quarantine.md` 与
`../docs/63-m8-notification-reliable-delivery.md`、以及
`../docs/64-m8-fulfillment-geo.md`、
`../docs/65-m8-product-reviews.md`、
`../docs/66-m8-catalog-search.md` 和
`../docs/67-m8-operational-analytics.md`。

## M7 scale-data and query tools

在业务 JVM 停止、七个核心中间件已经运行时生成或核对 Small 数据：

```powershell
./tools/prepare-m7-scale-data.ps1 -Action Seed -Scale Small
./tools/prepare-m7-scale-data.ps1 -Action Verify -Scale Small
```

运行 Catalog/Trade 正式查询基线：

```powershell
./tools/run-m7-scale-query-baseline.ps1 -Scale Small
```

工具会先通过 Catalog 应用 Flyway，再采集 offset、keyset、点查、执行计划、Gateway 负载和资源证据，并在结束时释放四个临时 JVM。观测栈、读副本和分片容器必须与该 Profile 互斥。完成后显式清理：

```powershell
./tools/prepare-m7-scale-data.ps1 -Action Remove -Scale Small
```

正式结果和当前边界见 `../docs/49-m7-scale-data-and-cursor-pagination.md`。

Catalog 真实读副本使用独立互斥 Profile，验证暂停复制、读己之写、恢复追平、
副本停机和主库回退：

```powershell
./tools/verify-m7-catalog-read-replica.ps1
```

脚本会执行机器级网络预检，只启动一个 512 MiB MySQL 副本和一个 Catalog
JVM，专项关闭 Catalog 缓存，并在结束时移除容器、临时复制账号、探针和
应用进程。当前主库 GTID 关闭，因此本机证据使用一致快照加 binlog 文件/位置，
不把它描述为生产自动故障转移。完整结果见
`../docs/51-m7-catalog-read-replica.md`。

Trade 两分片同样使用独立互斥 Profile。脚本创建两个临时 Trade schema，
逐片执行 Flyway，按 `user_id % 2` 验证奇偶用户聚合，并跑通支付、履约、
整单退货、库存回补和退款。每轮使用独立 RocketMQ Topic/Consumer Group，
结束后删除 Group、Retry/DLQ、Topic、schema、账号、JVM 和实验容器：

```powershell
./tools/verify-m7-trade-sharding.ps1
```

脚本采用两阶段启动，最多同时运行五个业务 JVM。它证明当前两片机制可行，
不代表历史归档、在线迁移和主动扩容已经完成。完整证据见
`../docs/52-m7-trade-sharding.md`。

历史归档工具不启动业务 JVM。它在两个真实 MySQL 上分别创建随机源/归档 schema，
执行 Trade V1–V14，验证提交后中断续跑、显式高水位刷新、重复执行、11 表指纹、
人为篡改拦截、切读门禁、只删除归档副本的回滚和回滚后重放：

```powershell
./tools/verify-m7-trade-archive-migration.ps1
```

对已有两片执行受控归档时使用分步命令：

```powershell
./tools/invoke-m7-trade-archive-migration.ps1 `
  -Action Initialize -JobId M7-ARCHIVE-20260722 `
  -CutoffAt '2026-06-01 00:00:00.000'
./tools/invoke-m7-trade-archive-migration.ps1 `
  -Action Migrate -JobId M7-ARCHIVE-20260722
./tools/invoke-m7-trade-archive-migration.ps1 `
  -Action Verify -JobId M7-ARCHIVE-20260722
./tools/invoke-m7-trade-archive-migration.ps1 `
  -Action Promote -JobId M7-ARCHIVE-20260722
```

`Promote` 只写切读门禁，不删除源事实；需要撤销时执行 `Rollback`。当前归档工具
本身不等价于 `user_id % 2 -> user_id % 4` 的主动重分片。完整证据见
`../docs/53-m7-trade-history-archive-migration.md`。

主动重分片使用同一个按需第二 MySQL Profile，但在两个物理 MySQL 上创建四个随机
目标 schema。验证器覆盖 NULL 消费所有者门禁、提交后中断、在线变化、最终写栅栏、
69 组全列指纹、篡改阻断、四片读取、受限回滚和回滚后重放：

```powershell
./tools/verify-m7-trade-resharding.ps1
```

应用 Profile `application-m7-trade-resharding.yml` 只接受连续命名的四片配置。
初始复制期间源可以继续写，但最终追平需要短维护写栅栏；没有反向复制，目标产生
新写后不能直接回滚。完整证据见
`../docs/54-m7-trade-active-resharding.md`。

## M6 flash-sale verification

第一批准入门禁使用真实 MySQL、Redis、Nacos、Marketing 与 Gateway 验证固定配额、一人一次、稳定令牌、Gateway 独立限流以及 Redis 失败关闭：

```powershell
./tools/verify-m6-flash-sale-admission.ps1 -EnableRedisFaultInjection
```

完整排队与毕业门禁使用真实 MySQL、Redis、Nacos、RocketMQ、Gateway 和六个业务服务，验证独立 Topic、Marketing Outbox、Trade 幂等建单、Inventory MySQL 最终裁决、结果回写、MQ 停机恢复和普通交易混合峰值：

```powershell
./tools/verify-m6-flash-sale-queue.ps1 -EnableMqFaultInjection
```

两个脚本都使用独立运行命名空间并在 `finally` 中清理本次 MySQL、Redis、Topic、消费组和应用进程。最终证据分别见 `docs/46-m6-flash-sale-admission-baseline.md` 与 `docs/47-m6-flash-sale-queue-and-graduation.md`。

## M5 capacity tools

Capture the host, runtime, container, MySQL, and Redis baseline without writing
credentials to the evidence file:

```powershell
./tools/capture-m5-environment.ps1
```

Run the dependency-free Node.js HTTP load runner:

```powershell
node ./tools/m5-http-load-runner.mjs `
  ./tools/m5-load-config.example.json `
  ./.run/m5-catalog-list.json

node --test ./tools/m5-http-load-runner.test.mjs
```

The runner records throughput, error rate, status distribution, P50/P95/P99,
response-contract failures, and per-scenario details. A short empty-Catalog run
is only a tool smoke test; formal M5 conclusions require fixed data, JVM and
container resources, repeated load levels, Prometheus/GC/connection/lock/MQ
evidence, and business correctness assertions. See
`docs/41-m5-capacity-methodology-and-first-baseline.md`.

Prepare or verify the deterministic M5 data set, then run the fixed-resource
Catalog/Trade query curve:

```powershell
./tools/prepare-m5-baseline-data.ps1 -Action Seed
./tools/prepare-m5-baseline-data.ps1 -Action Verify
./tools/run-m5-query-capacity.ps1 `
  -RequestsPerRun 1000 `
  -Repetitions 3 `
  -ConcurrencyLevels 1,5,10,20,50,100
```

The formal runner starts and stops only Gateway, Identity, Catalog, and Trade,
refreshes short-lived fixture JWTs before every protected run, and captures
real listener PIDs, GC logs, Actuator/Prometheus, MySQL/Redis, host, and
container evidence. It does not start the full middleware stack. Remove the
deterministic data only after all M5 comparisons that depend on it:

```powershell
./tools/prepare-m5-baseline-data.ps1 -Action Remove
```

The first formal comparison and the Trade order-list pagination/N+1 fix are
documented in `docs/42-m5-query-capacity-and-order-pagination.md`.

## M4 authoritative checkout verification

With the seven core middleware containers plus Gateway, Identity, Catalog,
Inventory, Trade, and Marketing already healthy, execute:

```powershell
./verify-m4-authoritative-checkout.ps1
```

The script creates isolated temporary facts and verifies the cart display
snapshot, current Catalog price, authoritative Inventory availability,
side-effect-free Marketing preview, stable order idempotency key, recovery by
that key, one Trade order / Inventory reservation / Marketing lock, browser-safe
string IDs, and cancellation release. It always removes its MySQL and Redis
fixtures in `finally`; cleanup failure fails the verification.

For a bounded manual browser inspection before automatic cancellation and
cleanup, use:

```powershell
./verify-m4-authoritative-checkout.ps1 -BrowserHoldSeconds 180
```

This option only delays cleanup and does not change the verified business path.
The disposable browser password and request key are written to a short-lived
fixture file under `.run`; stdout only reports the fixture path and non-secret
identity summary, and `finally` removes the file.
See `docs/36-m4-authoritative-checkout-and-order-recovery.md`.

## M4 payment and fulfillment recovery verification

With the required applications and middleware healthy, the two bounded M4
fault scripts verify browser-visible unknown-result recovery without changing
global network configuration:

```powershell
./verify-m4-payment-recovery.ps1
./verify-m4-fulfillment-timeline.ps1
```

The scripts start and stop their own one-shot local response-loss proxies on
ports `18601` and `18602` by default. `-GatewayBaseUrl`, `-ProxyPort`,
`-ArmFile`, `-ProxyEvidenceFile`, and `-BrowserFixtureFile` remain available
for bounded browser or external-proxy verification. Browser fixture passwords
are never printed to stdout and the short-lived fixture files are removed in
`finally`. The Payment script drops a real HTTP 200 payment-create response after the
owner domain has committed, then proves recovery by the original idempotency
key, one payment fact, cross-account 404 isolation, signed callback idempotency,
and Trade convergence. The Fulfillment script drops a real HTTP 200
confirm-receipt response, then proves `SIGNED` query recovery, append-only
history/logistics, cross-account isolation, and Trade `COMPLETED`. Both scripts
write ignored evidence under `backend/.run` and clean only their own fixtures.
See `docs/38-m4-payment-and-unknown-result-recovery.md` and
`docs/39-m4-fulfillment-and-logistics-timeline.md`.

## Identity API

All browser and admin clients call these routes through the gateway:

| Method | Path | Authentication |
| --- | --- | --- |
| `POST` | `/api/v1/identity/auth/register` | Public |
| `POST` | `/api/v1/identity/auth/login` | Public |
| `POST` | `/api/v1/identity/auth/refresh` | Refresh token |
| `POST` | `/api/v1/identity/auth/logout` | Refresh token |
| `GET` | `/api/v1/identity/me` | Bearer access token |
| `POST/GET` | `/api/v1/identity/addresses` | Bearer access token |
| `PUT/DELETE` | `/api/v1/identity/addresses/{addressId}` | Address owner |
| `POST` | `/api/v1/identity/addresses/{addressId}/default` | Address owner |
| `GET` | `/api/v1/identity/status` | Public |

Passwords use BCrypt. Access tokens expire after 15 minutes. Opaque refresh
tokens expire after seven days, are stored only as SHA-256 hashes, and rotate on
every use. Reusing a rotated or logged-out token returns `401`.

The first address becomes the default automatically. Each account can store at
most 20 addresses, and mutations are serialized on the account row so concurrent
default changes cannot leave multiple defaults. Trade reads an owned address only
through the protected `/api/v1/identity/internal/**` API.

Login failures are counted by normalized email. Five failures within 15 minutes
lock that identifier for 30 minutes. The gateway independently limits login,
registration, and refresh traffic by client IP. Both controls use Redis Lua
scripts in normal operation and bounded local Caffeine state when Redis is down.

## Catalog API

Public reads use `/api/v1/catalog/categories`, `/brands`, `/products`, and
`/products/{id}`. Only products in `ACTIVE` state are visible. Administrative
writes are under `/api/v1/catalog/admin/**` and require an `ADMIN` or `OPERATOR`
role from the identity JWT.

Product creation writes an SPU and at least one SKU in one MySQL transaction.
SKU prices use `DECIMAL(18,2)` / `BigDecimal`; catalog does not own inventory.
Publishing and updates use optimistic versions. Product images use a private
MinIO bucket: request a pre-signed PUT URL, upload directly, then confirm the
object so its metadata is persisted. A MinIO signing outage omits media URLs but
does not make text catalog reads fail.

## Inventory API

Public stock summaries use `/api/v1/inventory/stocks/{skuId}`. Warehouse and
stock-adjustment commands are under `/api/v1/inventory/admin/**` and require an
`ADMIN` or `WAREHOUSE` role. Reservation commands are under
`/api/v1/inventory/internal/**`; they require the trusted backend service identity
and are blocked at the public gateway.

MySQL is the inventory source of truth. Reservation uses conditional updates,
never read-then-write stock deduction. `reservationNo` and adjustment movement
numbers are idempotency keys backed by unique indexes and request hashes.
Confirming reduces both `on_hand` and `reserved`; releasing or expiring reduces
only `reserved`. Every mutation writes an immutable stock movement and an
Outbox event in the same local transaction. RocketMQ failure leaves the event
pending for retry and does not roll back the stock transaction.

Inventory also runs an owner-domain, read-only reconciliation scan across balances,
active reservations, immutable movements, return records, and Outbox facts. Issues
are persisted as `OPEN`/`RESOLVED`; only `ADMIN` can query them under
`/api/v1/inventory/admin/reconciliation/issues`. The scan reports inconsistencies
but never changes stock. See `docs/21-inventory-reconciliation.md`.

## Trade API

Authenticated customers use `/api/v1/trade/cart/items` and
`/api/v1/trade/orders`. Every create-order request requires an
`Idempotency-Key` and `addressId`. Trade reads the current catalog and owned
identity address, persists product/SKU/price/address snapshots, creates
`PENDING_STOCK`, then calls inventory with a stable
reservation number. A successful reservation moves the order to
`PENDING_PAYMENT`; shortage closes it without pretending that an order can be
paid.

Remote calls are outside MySQL transactions. Inventory outages leave the order
in `PENDING_STOCK`; cancellation outages leave it in `CANCELING`. A scheduled
recovery job retries both operations. Payment timeout uses the same cancellation
path, so release remains idempotent and auditable. Every state change writes
history and a trade Outbox event in the same local transaction.

## Payment API

Authenticated customers create and read payments under `/api/v1/payment/payments`.
Creation requires `Idempotency-Key`; payment verifies the order through a protected
trade-service internal endpoint and never reads the trade database. The local mock
channel callback is public because a real provider cannot present a customer JWT,
but every callback must pass HMAC-SHA256 verification and a five-minute timestamp
window. Callback event IDs, payment transactions, and payment success events are
uniquely persisted so duplicate delivery has no duplicate financial side effect.

Payment success is not a synchronous cross-service transaction. `payment-service`
writes `PaymentSucceeded` to its Outbox. `trade-service` first persists the
consumer fact and `PENDING_PAYMENT -> PAYMENT_CONFIRMING`, then calls Inventory
outside the Trade transaction with the original reservation number. Only an
authoritative `CONFIRMED` reservation allows a second Trade transaction to write
`PAID` and `OrderPaid`; an unknown result remains recoverable, while a terminal
released/expired/rejected reservation becomes `PAYMENT_EXCEPTION`. Inventory,
Marketing, and Fulfillment consume the resulting order fact idempotently.

## Fulfillment API

`fulfillment-service` consumes `OrderPaid` and creates exactly one fulfillment
order per trade order. The event carries the immutable delivery snapshot, so
later identity-address edits or deletion cannot change an existing shipment.
Customers read only their own logistics through
`/api/v1/fulfillment/orders`. Warehouse and administrator commands under
`/api/v1/fulfillment/admin/**` advance the explicit picking, packing, shipping,
transit, delivery, and signed state machine. Carrier event identifiers and
tracking numbers are unique; logistics traces are append-only.

Each coordinate-bearing trace also updates a Fulfillment-owned
`shipment_latest_position` projection in the same MySQL transaction. MySQL 8.4
stores a `POINT SRID 4326`, maintains a spatial index, and executes bounded
`ST_Distance_Sphere` nearby queries. Redis GEO is only an after-commit,
rebuildable acceleration layer: missing or unavailable cache data falls back to
MySQL and attempts read repair. Customers can read only their own current
position, while administrators and warehouse staff can run bounded nearby
queries; only administrators can rebuild the Redis projection.

Fulfillment writes lifecycle facts to `ecommerce-logistics-events`. Trade
consumes `FulfillmentCreated`, `ShipmentDispatched`, and `ShipmentSigned` in
local transactions to advance `PAID -> FULFILLING -> SHIPPED -> COMPLETED`.
MQ failure leaves Outbox rows pending and never fabricates an order state.

## Whole-order return and refund

Completed orders can enter one whole-order after-sale within the configured
application window. Trade snapshots the original per-line payable allocation,
Fulfillment owns return shipment and inspection, Inventory performs an
idempotent stock return, and Payment creates one refund per after-sale. Refund
channel dispatch is persisted and retryable; a sent request remains
`PROCESSING` until an independently signed callback arrives. Poison messages
are version-checked and moved to each service's `consumer_failure` record after
the configured delivery limit.

## Marketing price API

`marketing-service` owns fixed-amount coupon, red-packet, and subsidy rules.
An order may use at most one benefit of each type and may stack all three.
Rules can be nationwide or limited by six-digit province, city, or district
codes. Trade locks pricing before inventory reservation and persists the
returned totals, per-item payable amounts, and benefit-to-line allocations as
immutable snapshots.

Authenticated customers may call `/api/v1/marketing/pricing-previews` to reuse
the same eligibility, stacking, and allocation calculation without creating a
pricing lock. A preview does not change a benefit from `AVAILABLE`, does not
reserve inventory, and cannot be treated as an order commitment.

The marketing lock is idempotent by order number. `OrderCanceled` and
`OrderClosed` release locked benefits; `OrderPaid` redeems them. Lifecycle
events are deduplicated in the same local transaction as the state change.

## Outbox operational metrics

Trade, Inventory, Payment, and Fulfillment expose their Actuator `metrics`
endpoint only to an authenticated `ADMIN`. Their Outbox publishers share these
metric names and a bounded `service` tag:

- `ecommerce.outbox.pending`: events that have not reached `PUBLISHED`;
- `ecommerce.outbox.oldest.age`: age in seconds of the oldest unpublished event;
- `ecommerce.outbox.publications`: `success`, `failure`, or `state_conflict` outcomes;
- `ecommerce.outbox.claims`: multi-instance `contended` claims and recovered stale leases;
- `ecommerce.outbox.publish.duration`: local publish-and-persist duration.

The backlog gauges read the service-owned Outbox table and return `NaN` when
that read fails, so a monitoring read cannot pretend the backlog is zero.

## Consumer failure diagnostics

Trade, Inventory, Payment, and Fulfillment expose a service-local, read-only
`/actuator/consumerfailures` endpoint to `ADMIN` only. It reports `RETRYING`,
`NEEDS_ATTENTION`, and `RECOVERED` totals, the oldest active failure age, and a
bounded list of the most recently failed message IDs, consumer groups,
attempts, errors, and timestamps. The original message body remains in the
owner service database for later authorized recovery but is intentionally not
returned by this endpoint.

The same services register these shared metrics with bounded `service`,
`status`, and `outcome` tags:

- `ecommerce.consumer.failure.active`: active `retrying` and `needs_attention` records;
- `ecommerce.consumer.failure.oldest.age`: age of the oldest failure not yet recovered;
- `ecommerce.consumer.failure.transitions`: recorded `retrying`, `needs_attention`, and `recovered` transitions.

Database read failures produce `NaN` gauges instead of a false zero. This
endpoint does not replay messages or mutate domain state; authorized replay
and its audit trail are a separate M2 batch.

## Business processing diagnostics

Trade and Payment expose `/actuator/businessprocesses` to `ADMIN` only. Trade
reports recovering orders and active after-sales; Payment reports processing
or failed refunds and refund dispatch records that require attention. The
response contains bounded business references, stages, last errors, and state
ages, but does not expose customer identity or message payloads.

- `ecommerce.business.process.active`: active records by bounded `service`, `domain`, and `status`;
- `ecommerce.business.process.oldest.age`: age in seconds of the oldest record in that state.

Normal customer or warehouse waiting states remain visible so later alert
thresholds can distinguish expected waiting from system recovery states. The
endpoint is read-only and queries only the owner service schema; it does not
create a central cross-database operations table.

## Secure Prometheus export

Inventory, Trade, Payment, and Fulfillment expose `/actuator/prometheus` through
the Prometheus registry. The endpoint is never anonymous: `ADMIN` JWTs remain
valid for diagnosis, while the scraper uses a separate `X-Metrics-Token` role
whose secret is generated into ignored local files. A missing token disables
the scraper identity, a configured token shorter than 32 characters fails
startup, and an invalid header receives `401`.

Prometheus, Alertmanager, and Grafana are an optional Compose profile, not an
application dependency. Configuration, alert semantics, credential rotation,
and the real-stack verification command are documented in
`docs/18-observability-and-alerting.md`.

## Domain-authorized compensation

Payment owns the first controlled compensation command. An `ADMIN` may retry a
refund dispatch only after automatic dispatch is exhausted or the channel has
explicitly failed. The request requires an `Idempotency-Key` and a reason;
accepted and rejected commands append an audit row in the same Payment schema.
Duplicate commands do not reset an already re-dispatched refund, and concurrent
commands for one refund are serialized by the database. See
`docs/19-compensation-governance.md`.

Payment also runs an owner-domain reconciliation scan for payment/refund status,
channel transactions, success Outbox facts, and original-payment consistency.
Findings are persisted as `OPEN`/`RESOLVED`, exposed read-only to `ADMIN`, and
reported through a bounded Prometheus gauge. The scan never repairs financial
facts automatically. See `docs/20-payment-reconciliation.md`.

The same bounded issue metric covers Inventory, Trade, and Fulfillment
reconciliation. Prometheus alerts by the `service` label, so financial, stock,
order/after-sale, and fulfillment/return inconsistencies retain their domain
owner without a central component reading or updating multiple schemas. See
`docs/25-trade-fulfillment-reconciliation.md`.

## Synchronous call resilience

Two representative Resilience4j boundaries are governed independently:
Payment's read-only lookup of Trade payment context, and Trade's idempotent
pricing-lock command to Marketing. Connection/read timeouts, at most two total
attempts, retry wait, circuit windows, half-open probes, and semaphore bulkheads
are bounded by validated per-operation configuration. Transport failures and
`5xx` responses are eligible for finite retry; business `4xx` responses are not
retried or recorded by the circuit. An unavailable Trade fact creates no payment
row. An unavailable Marketing fact leaves the order in recoverable
`PENDING_STOCK` and prevents inventory reservation. Native Resilience4j metrics
plus a bounded `ecommerce.http.client.resilience.rejections` counter drive the
dashboard and alerts. This is not a claim that every synchronous call has been
governed. See `docs/22-synchronous-call-resilience.md`.

Trade order recovery runs on a dedicated, single-threaded
`tradeOrderRecoveryScheduler`; the existing default single-thread scheduler
continues to serve MQ long polls and Outbox without silently increasing their
concurrency. Actuator exposes native executor active/queued/pool metrics plus
bounded task execution, duration, running, and completion-age metrics. The live
Marketing outage test proves a second recovery attempt in about 6.33 seconds
while all long-poll consumers remain enabled. See
`docs/23-trade-scheduling-isolation.md`.

## Trade Outbox multi-instance verification

Trade now claims Outbox work in a `READ_COMMITTED` transaction using
`FOR UPDATE SKIP LOCKED`, an explicit publisher owner and lease deadline, and a
database predecessor guard that prevents a later event from overtaking an
unpublished event from the same aggregate. Published/failed updates are fenced
by both owner and unexpired lease.

With MySQL and RocketMQ already running, execute:

```powershell
./verify-trade-outbox-multi-instance.ps1
```

The script starts 1, 2, and 3 isolated Trade publisher processes and publishes
1000 real events per run. It verifies all instances participate, final Outbox
convergence, aggregate order, retry/claim budgets, absence of state conflicts
and InnoDB deadlock logs, and expired-owner recovery. Evidence is written to
`.run/trade-outbox-multi-instance.json`. The latest 2026-07-20 result was 212.491,
218.639, and 195.832 events/s respectively, so two instances are the current
single-machine performance default; three instances remain the correctness and
failure-test scale. See `docs/27-m3-trade-outbox-multi-instance.md`.

## Trade container multi-instance verification

Trade has a non-root JRE 17 image, Docker liveness probe, unique container-derived
Nacos instance and Outbox publisher identities, Spring graceful shutdown, and a
bounded Outbox executor drain. RocketMQ Broker and cluster-mode Proxy use separate
containers sharing one network namespace so the Proxy can return the host or
Compose-network endpoint actually used by each client without changing the fixed
gRPC port 18082.

With MySQL, Nacos, RocketMQ NameServer, Broker, and Proxy already running, execute:

```powershell
./verify-trade-container-multi-instance.ps1 `
  -SkipNetworkPreflight `
  -EventCount 1000 `
  -TimeoutSeconds 180
```

The script builds one image, scales Trade through 1, 2, and 3 instances, verifies
Docker and Nacos health, publishes 1000 real Outbox events, requires all three
publishers to participate, checks aggregate ordering and retry/state-conflict
budgets, and stops one instance gracefully. The formal result converged in
16212.205 ms with a 350/311/339 distribution, zero retries, zero state conflicts,
and zero order violations. Evidence is written to
`.run/trade-container-multi-instance.json`. For a smaller connectivity smoke such
as 100 events, add `-AllowPartialPublisherParticipation`; a small batch may be
claimed before every publisher receives work. See
`docs/28-m3-trade-container-multi-instance.md`.

## Trade consumer multi-instance and process-termination verification

The `PaymentSucceeded` consumer exposes acknowledgement and redelivery
acknowledgement counters and supports three event-scoped, default-off process
termination points. The verification uses a disposable real RocketMQ 5.3.2
NameServer/Broker/Proxy set so a damaged shared Timer Store cannot invalidate
redelivery evidence or require deletion of shared broker data.

Run:

```powershell
./verify-trade-consumer-multi-instance.ps1 `
  -SkipNetworkPreflight `
  -SkipBuild `
  -EventCount 1000 `
  -TimeoutSeconds 300
```

The formal run completed 1000 acknowledgements at each of the 1/2/3 instance
scales, with every instance participating. Re-delivering the three-instance
batch left exactly 1000 payment histories, `OrderPaid` Outbox rows, and consumed
event records. `OUTBOX_BEFORE_PUBLISH`, `OUTBOX_AFTER_BROKER_ACK`, and
`CONSUMER_AFTER_COMMIT` each exited with code 91 and recovered to
`PUBLISHED|PAID|1|1|1|1`; the final boundary recorded one redelivery
acknowledgement. Evidence is written to
`.run/trade-consumer-multi-instance.json`.

## Gateway/Nacos rolling-upgrade verification

Gateway has a non-root JRE 17 image and an on-demand `m3-gateway` Compose
profile. Trade status responses and Nacos metadata expose the operational
instance and release identifiers used by the release gate.

Run:

```powershell
./verify-gateway-rolling-upgrade.ps1 `
  -SkipNetworkPreflight `
  -TimeoutSeconds 300 `
  -ProbeIntervalMilliseconds 100
```

The script starts two stable Trade instances, adds and promotes candidates,
sets the old instance to `enabled=false`, waits for 30 consecutive Gateway
requests that no longer select it, and only then sends SIGTERM. A failed
candidate uses `register-enabled=false`, so its expected database startup
failure cannot enter Nacos. The formal run completed 1439 continuous Gateway
requests with zero HTTP or business failures and zero unexpected releases.
The disposable release network gives each version a unique service-discovery
IP and is removed with the experiment. Evidence is written to
`.run/gateway-rolling-upgrade.json`. See
`docs/29-m3-consumer-fault-and-release-governance.md`.

## Real foundation smoke test

Start the middleware, initialize its resources, and run:

```powershell
../deploy/docker/bootstrap-resources.ps1
./run-foundation-smoke.ps1
```

Add `-EnableObservability` to start the optional observability stack during the
live application window and verify four authenticated scrape targets, alert
rules, Alertmanager connectivity, Tempo readiness, and the provisioned Grafana
Prometheus/Tempo datasources and dashboard.

Add `-EnableDistributedTracing` to enable 100% OTLP export for the smoke process,
persist the payment and refund callback W3C carriers through the Payment Outbox,
propagate them through RocketMQ, and query Tempo for two traces containing both
`payment-service` and `trade-service` plus the matching producer/consumer spans. Combine it with
`-EnableObservability` for the complete M2 observability proof. Export remains
disabled by default. See `docs/24-distributed-tracing.md`.

Add `-EnableSynchronousResilienceFaultInjection` to stop Trade after a real
payable order exists, open the Payment circuit with bounded failures, verify a
prompt rejection and zero Payment rows, restart Trade, and prove the two
half-open probes close the circuit without creating a duplicate payment.

Add `-EnableTradeMarketingResilienceFaultInjection` to stop Marketing, prove the
dedicated order-recovery scheduler retries within 12 seconds while MQ long polls
remain enabled, open the Trade circuit with five logical failures, and verify the
fifth order is rejected before remote I/O. Restart Marketing to prove five unique
pricing locks converge before all stock is released by cancellation.

Add `-EnableCapacityBaseline` for two 1000-request, 100-concurrency correctness
scenarios, plus 100-way same-order-key, same-payment-callback, and
same-refund-callback replay. The command writes ignored evidence to
`.run/capacity-baseline.json`, polls MySQL to the final state, and validates stock
against the number of reservations that are still active when the asynchronous
chain converges. The 2026-07-18 baseline and its Outbox tail-latency finding are
documented in `docs/26-m2-graduation-and-capacity-admission.md`.

The smoke script first calls `D:\DevTools\Network\check-dev-network.ps1` and
never starts missing containers automatically. It does not stop Redis by
default. Run `./run-foundation-smoke.ps1 -EnableRedisFaultInjection` only when
an intentional Redis outage test is desired; `-SkipNetworkPreflight` is for an
equivalent manual network check, not a normal shortcut.

The script loads ignored local credentials from `deploy/docker/.env`, packages
the backend, starts identity, catalog, inventory, marketing, trade, payment, fulfillment, and gateway in hidden
processes, and uses real MySQL, Nacos, Redis, RocketMQ, and MinIO containers. In
addition to the identity and degradation checks, it proves catalog RBAC, draft
isolation, product publication, pre-signed image upload/download, inventory
RBAC, 100-request stock competition, reservation idempotency, confirm/release,
and Outbox publication. It also runs 30 real orders against five units of stock,
checks address ownership, address edit/delete isolation, order snapshots,
  idempotency/cancellation, verifies a signed duplicate payment callback, and
  waits for `PaymentSucceeded -> PAYMENT_CONFIRMING -> inventory confirmation
  -> OrderPaid -> fulfillment creation`. It then executes picking, packing,
shipping, three logistics nodes, signing, and verifies the trade order becomes
`COMPLETED`. It then runs the whole-order after-sale, return inspection,
idempotent stock replenishment, persisted refund dispatch, signed refund
callback, and final after-sale completion. It fault-injects missing Payment and
Inventory success events plus Trade order-completion and Fulfillment
shipment-signing events, waits for owner-domain detection, restores the facts,
and verifies automatic issue closure in all four owner domains. Temporary database rows, objects, Redis keys, and
processes are removed in `finally`. Runtime logs are written to the ignored
`backend/.run` directory.

Default local endpoints:

- Gateway: `http://127.0.0.1:18000`
- Identity service: `http://127.0.0.1:18101`
- Catalog service: `http://127.0.0.1:18102`
- Inventory service: `http://127.0.0.1:18103`
- Trade service: `http://127.0.0.1:18104`
- Payment service: `http://127.0.0.1:18105`
- Fulfillment service: `http://127.0.0.1:18106`
- Marketing service: `http://127.0.0.1:18107`
- Chat service: `http://127.0.0.1:18108`
- Notification service: `http://127.0.0.1:18109`
- Routed status: `http://127.0.0.1:18000/api/v1/identity/status`

Do not expose service ports to browser clients in production; only the gateway
is a public application entry point.
