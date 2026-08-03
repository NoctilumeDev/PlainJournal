# 素简记（Plain Journal）

> 把复杂留给系统，把简单交给用户。

素简记是一个边界完整的自营 B2C 分布式电商平台，覆盖多实例集群、高并发与数据规模化实践。项目使用 Spring Boot 3、JDK 17 和 Vue 3，在个人开发环境能够承受的范围内，通过真实中间件、故障恢复和量化测试验证分布式机制，而不是复刻任何既有商城。多商户平台化不再加入本仓库，未来将以独立的“素简记 Pro”承接多主体订单、分账结算和 Go 异构服务演进。

项目采用“能力完整、组件克制、规模缩比、证据真实”的技术准入原则：常规开发按需运行当前十一个单实例，集群实验只把代表服务扩为三个实例；同一能力只保留一种主线实现，替代框架通过按需 POC 验证，不以常驻容器和依赖数量衡量完成度。

## 成品预览

顾客端以“青荷”为默认主题，强调商品事实、留白和连续购买旅程，不使用促销广告墙。

![素简记顾客端首页](docs/assets/v7-4/storefront-home.jpg)

![素简记商品详情](docs/assets/v7-4/storefront-product.jpg)

管理端按员工角色继续收窄权限，高风险页面只呈现所有者域事实、合法补偿入口和追加式
审计，不提供直接改写成功状态的万能后台。

![素简记补偿与对账工作区](docs/assets/v7-4/admin-governance.jpg)

## 快速演示

本地演示使用固定内存夹具，只验证前端交互和视觉，不替代真实中间件证据：

```powershell
git clone https://github.com/NoctilumeDev/PlainJournal.git
cd PlainJournal\frontend
pnpm install --frozen-lockfile
pnpm demo:start
```

打开：

- 顾客端：`http://127.0.0.1:18300`
- 管理端：`http://127.0.0.1:18301`

演示账号：

| 身份 | 邮箱 | 密码 |
| --- | --- | --- |
| 顾客 | `reader@example.com` | `ReaderPass123` |
| 第二顾客 | `reader-two@example.com` | `ReaderPass123` |
| 管理员 | `admin@example.com` | `AdminPass123` |

完成后执行：

```powershell
pnpm demo:stop
```

真实 Nginx 镜像、缓存、404、同源代理和不可变标签回退见
[前端静态部署说明](frontend/deploy/nginx/README.md)。项目采用
[Apache License 2.0](LICENSE) 开源；正式发布步骤与冻结证据见
[v1.0 发布清单](.github/RELEASE_CHECKLIST.md)。

项目已经完成架构设计、本地中间件验证、后端基础、Identity、Catalog、Inventory、Trade、Payment、Fulfillment、Marketing、Chat、Notification 和 Analytics 垂直切片，以及可降级的 Redis 流量保护。注册登录、商品发布、库存预占、营销锁价、支付、履约、整单退货、库存回补和退款已经形成一条跨服务业务链。订单、地址、商品、优惠和退款金额均读取不可变历史快照；跨服务使用本地事务、Outbox、RocketMQ、幂等消费、有限重试和补偿记录收敛。

当前坐标：M1–M8 已关闭。2026-07-28 又按“代码证明 + 自动化测试 + 真实运行证据”三层标准完成 M0–M8 全域复审，覆盖所有者数据库与关系凭据隔离、数据库时间、同步调用事务边界、支付/库存因果关系、结果未知恢复、多实例抢占、滚动发布、双版本消息语义、1000/100 容量、M6 秒杀、M7 分片/副本/重分片，以及 M8 Chat/Notification/GEO/评价/搜索/Analytics。当前仓库进入前端产品化、演示、部署和 GitHub v1.0 发布收口，不实施原 M9；三个商户与 Go 异构服务只保留为未来“素简记 Pro”的独立架构课题。

最新代码门禁：2026-08-03 正式发布前重新执行后端全量 `mvn clean verify`，产生 100 份 Surefire 报告，共 436 个测试通过，0 失败、0 错误、0 跳过；2026-07-30 的独立 PMD 仍为 12 份报告、0 违规，SpotBugs 低阈值扫描仍为 12 份报告、Priority 1 为 0、Priority 2 为 247、Priority 3 为 66、缺失分析类为 0。2026-08-03 前端 V1–V7.4 已完成并重新串行通过 303 个单元/契约测试、60 个开发态 Playwright E2E、3 个生产构建 E2E、28 条分层规则、3 条交付审计规则、3 条生产部署规则、3 条发布材料规则、全部类型检查和两端生产构建。V7.4 还在核心中间件运行数为 0 的互斥 Docker 窗口完成双镜像真实构建、OCI 元数据、HEALTHCHECK、缓存、缺失资源 404、同源 API，以及 `rc.1 → rc.0 → rc.1` 的实际镜像 ID 回退与恢复。两张商品图和一张履约图保留 PNG fallback，同时提供 18 个 AVIF/WebP 响应式变体；真实 Chromium 与内置浏览器确认使用 AVIF、无横向溢出、控制台零 warning/error。

真实 Checkout 门禁以 7 个 core 中间件和完整业务应用验证价格、库存、权益、幂等恢复、取消释放与 6 次管理边界 403；订单/Payment 门禁又以 Gateway、Identity、Catalog、Inventory、Trade、Payment、Fulfillment、Marketing 和内置浏览器/F12 验证支付创建上游 200 后响应丢失、原键恢复、取消/支付互斥、跨账户 404，以及 Payment SUCCESS、Trade PAID→FULFILLING、Inventory CONFIRMED、Fulfillment CREATED 的数据库收敛。最新 Fulfillment 前端门禁在相同真实栈上验证确认收货上游 200 后丢弃 2863 字节响应、按所有者事实恢复 `SIGNED`、Trade 收敛 `COMPLETED`、中文快照完整、桌面/移动端无溢出，并在结束后确认五域 run-scoped 数据、项目端口、JVM 与 Vite 全部归零。售后前端第九批进一步把 Trade 售后、Fulfillment 退货和 Payment 退款拆为三个事实积木；当前受控内置浏览器验证寄回后状态为 `RETURNING / RETURNING / PROCESSING`、桌面/移动端无溢出且控制台零错误。评价第十批把 Catalog 评价事实、订单评价意图和商品参与动作拆开；自动化主动丢弃提交响应后按 Catalog 资格恢复且只有一次 POST，人工浏览器又完成提交、商品页回显、点赞和举报，桌面/移动端均只有一个 `main`、无溢出且控制台零错误。真实资金、逆向链和评价 MQ/MySQL 结论仍由既有全栈证据裁决。真实 Chat 浏览器与 F12/CDP 复验继续通过认领前正文隔离、无需刷新同步、响应丢失恢复、23 个授权请求、7 个 WebSocket 101 和零页面/网络错误。自动化门禁使用 H2 和受控浏览器夹具，不替代真实 MySQL、Redis、Nacos、RocketMQ、MinIO、ClamAV、SMTP、OpenSearch、多实例和故障证据；完整三层矩阵见 [M0–M8 三层证据审查](docs/69-m0-m8-pre-m9-three-layer-audit-20260728.md)、[前端 Fulfillment 分层第八批](docs/78-frontend-fulfillment-layering-eighth-slice-20260730.md)、[前端售后三域分层第九批](docs/79-frontend-after-sale-layering-ninth-slice-20260801.md)和[前端评价分层第十批](docs/80-frontend-review-layering-tenth-slice-20260801.md)。

2026-07-21 的 M0 至 M4 收口复验再次完成 1000 请求、100 并发容量场景：Inventory 严格为 100 笔预占、900 笔拒绝，Trade 严格为 100 笔初始可支付、900 笔缺货关闭，传输错误为 0，库存方程与同键幂等成立；支付链 29.067 秒收敛，未发布 Trade Outbox 为 0，未付款预占没有在收敛期间过期。完整冒烟同时通过同步熔断/舱壁/有限重试、Redis 故障降级、四域对账、真实 Prometheus/Alertmanager/Grafana/Tempo、库存提交后响应丢失恢复，以及 M4 权威结算、支付创建响应丢失和确认收货响应丢失。最终审查还修复了 Inventory 预占幂等哈希遗漏 `expiresAt`、M4 故障脚本依赖外部代理和浏览器 DTO 注解污染 `OrderPaid` Outbox 三类契约问题。详见 [M3 双版本兼容、滚动发布与容量复测](docs/31-m3-dual-version-and-capacity.md)、[M4 前端就绪与架构基线](docs/32-m4-frontend-readiness-and-architecture.md)、[全项目审查与质量门禁](docs/33-project-wide-audit-and-quality-gate.md)、[M4 Payment 与结果未知恢复](docs/38-m4-payment-and-unknown-result-recovery.md)、[M4 履约与物流时间线](docs/39-m4-fulfillment-and-logistics-timeline.md)及 [M4 顾客售后、管理端与毕业报告](docs/40-m4-customer-after-sale-admin-and-graduation.md)。

M0–M5 全量回归已在最终代码状态完成：后端 179 tests、PMD 0 违规、SpotBugs 0 条 Priority 1，前端 70 Vitest + 2 E2E，真实五中间件正逆向链、1000/100 并发正确性、多实例/滚动发布/双版本兼容、观测追踪和 M5 基线清理全部通过。详见 [M0–M5 全量回归与毕业收口](docs/44-m0-m5-full-regression-20260721.md)。

2026-07-22 又完成 M5.5 仓库治理门禁：重新盘点全部目录和未提交工作树，复核死代码、依赖误报、脚本重复、Node 运行时、生成产物和文档链接；前端正式启用未使用符号检查，M5 查询/缓存工具统一 Node 解析规则，随后再次通过后端 179 tests、PMD、SpotBugs、前端 70 Vitest + 2 E2E 和依赖审计。详见 [M5.5 仓库治理与进入下一阶段门禁](docs/45-m5-5-repository-governance.md)。

M6 已完成独立活动链路：Marketing 持有活动与准入 MySQL 事实，Gateway 与 Redis Lua 负责入口限流，Marketing Outbox 通过独立 RocketMQ Topic 排队，Trade 幂等建单并由 Inventory MySQL 条件更新完成最终库存裁决，结果事件再回写 Marketing。最终 1000 请求/100 并发严格得到 100 个准入和 900 个售罄；MQ 停机期间新增接受事实保留在 Outbox，恢复后 101 条请求全部收敛为 101 个订单，库存为 `on_hand=101,reserved=101`，未发布、处理中、失败和需人工处理均为 0。普通下单、支付创建和退款查询混合峰值 300 请求/30 并发全部成功，P95 为 873.21 ms。详见 [M6 第一批：秒杀活动准入基线](docs/46-m6-flash-sale-admission-baseline.md)、[M6 秒杀排队、最终裁决与毕业报告](docs/47-m6-flash-sale-queue-and-graduation.md)和 [M0–M6 全量回归与毕业收口](docs/48-m0-m6-full-regression-20260722.md)。

M7 前五批完成了规模数据与游标分页、41/10/12 位分布式 ID、Catalog 真实读副本、Trade `user_id % 2` 两分片和历史归档闭环。第六批完成受控 `user_id % 2 -> user_id % 4` 主动重分片：提交后中断续跑、在线新增/更新/删除、最终写栅栏、69 组全列指纹、篡改拦截、四片 JVM 路由、跨所有者 404、受限回滚和回滚后重放全部通过；源事实未删除，实验资源无残留。最终全量门禁中的 Trade 模块为 104 tests、PMD 0、SpotBugs Priority 1 为 0。该实验需要最终短维护写栅栏，不冒充生产无停机 CDC；目标产生新写后不能直接回滚。详见 [M7 第一批](docs/49-m7-scale-data-and-cursor-pagination.md)、[M7 第二批](docs/50-m7-distributed-id.md)、[M7 第三批](docs/51-m7-catalog-read-replica.md)、[M7 第四批](docs/52-m7-trade-sharding.md)、[M7 第五批](docs/53-m7-trade-history-archive-migration.md)和 [M7 第六批：Trade 主动 2→4 重分片](docs/54-m7-trade-active-resharding.md)。

2026-07-23 完成 M0–M7 全面审查与回归门禁。审查补强了同步响应身份校验、Trade 取消前事实核对、MQ `payloadVersion`/`userId` 契约和 Trade 直连 Marketing 配置；随后按互斥 Profile 串行重跑真实正逆向交易、观测追踪、同步韧性、1000/100 并发、多实例、滚动升级、双版本、前端结果未知恢复、M5 容量与缓存、M6 秒杀和 M7 六批专项。最终后端 248 个测试、PMD、SpotBugs 分类审查、前端完整门禁、依赖漏洞审计、脚本/Compose/Markdown 门禁和临时资源清理全部通过，M8 准入。详见 [M0–M7 全面审查与回归门禁](docs/55-m0-m7-full-audit-and-regression-20260723.md)。

同日完成 M8 第一批可靠聊天持久化：新增 `chat-service`（18108）及 Gateway 路由，MySQL 是会话、消息、顺序和已读事实；发送接口只在消息与 Outbox 同一事务提交后返回 `STORED`。8 路并发会话重试与 16 路并发消息重试均收敛为一个事实；真实 Gateway + Nacos + MySQL 验证通过，测试数据和两个临时 JVM 均清理。详见 [M8 第一批：可靠聊天持久化与客户端幂等](docs/56-m8-chat-reliable-persistence.md)。

M8 第二批继续完成实时路由闭环：Outbox 通过多实例租约发布 `ChatMessageStored`，共享 Dispatcher 查询 Redis 在线节点，再以独立 Topic 和节点 Tag 投递 `ChatDeliveryRequested`；目标节点从 MySQL 读取正文并经 JWT WebSocket 推送。真实双 Chat 实例验证覆盖 Broker 故障保持 `STORED/PENDING`、恢复后跨节点投递、节点退出路由过期、`OFFLINE` 回执和重连回放，正文在 Outbox 中命中为 0，最终 MySQL、Redis、端口和 JVM 均无残留。详见 [M8 第二批：聊天实时路由、跨节点投递与离线回放](docs/57-m8-chat-realtime-routing.md)。

M8 第三批完成附件存储与授权闭环：上传意图并发重试收敛，私有 MinIO 对象经大小、MIME、文件头和完整 SHA-256 确认后才能在消息事务中绑定；下载前重新校验会话成员和对象完整性，同尺寸覆盖返回 422，过期孤儿使用 MySQL 抢占状态机清理。真实 Gateway、Nacos、MySQL、MinIO 验证和最终零残留通过。详见 [M8 第三批：聊天附件存储、完整性与授权下载](docs/58-m8-chat-attachment-storage-and-authorization.md)。

M8 第四批补齐浏览器原生 WebSocket 握手认证：长期 JWT 只用于受保护 REST 换取短期不透明票据，Redis 以票据 SHA-256 摘要建键并用 Lua 原子 `GET + DEL` 保证跨实例单次消费；票据绑定环境、用户、角色、路径和过期时间。真实 Gateway、双 Chat 实例和 Redis 故障节点验证覆盖重放/过期拒绝、Header JWT 兼容、签发与握手失败关闭、原票据不入应用日志和最终零残留。详见 [M8 第四批：浏览器 WebSocket 短期握手票据](docs/59-m8-chat-browser-websocket-ticket.md)。

M8 第五批补齐 Chat 持久化消费失败治理，并在整体审查中修正恢复所有权：`ChatMessageStored` 与 `ChatDeliveryRequested` 先把临时失败写入本服务 `consumer_failure` 的 `RETRYING + next_attempt_at`，提交成功后 ACK 原消息，再由多实例安全的 MySQL 租约作业有限重试；无效契约或预算耗尽进入 `NEEDS_ATTENTION`，成功后转为 `RECOVERED`。两次 360 秒真实失败基线证明不能把 RocketMQ POP revive 当作唯一恢复保障；最终 Redis 停机/恢复、Actuator/Prometheus、原始载荷隐藏和零残留通过。详见 [M8 第五批：Chat 持久化消费失败治理](docs/60-m8-chat-consumer-failure-governance.md)。

M8 第六批完成顾客端与客服端文本会话工作区：创建与发送响应丢失时复用原幂等键并从 MySQL 权威历史恢复；客服认领前不能读取私聊正文，两端使用短期单次票据建立真实 WebSocket。真实浏览器验证覆盖无需刷新实时回复、刷新恢复、Outbox 正文零泄漏、固定专用 Dispatcher 验证组和最终业务数据/失败台账/Redis/端口/进程零残留。当前门禁为 `platform-common` 14 tests、`chat-service` 38 tests、83 个 Vitest 和 4 个 Playwright E2E。详见 [M8 第六批：顾客端与客服端 Chat 会话工作区](docs/61-m8-chat-frontend-workspace.md)。

M8 第七批完成附件隔离、恶意文件扫描与审计恢复：上传确认只进入 `SCAN_PENDING`，真实 ClamAV 流式扫描通过后才进入 `READY`；EICAR 命中进入 `INFECTED` 并禁止绑定。扫描器停机时两次有限重试后进入 `SCAN_NEEDS_ATTENTION`，只有管理员幂等、带原因和追加式审计的命令可以重新进入扫描，不能直接标记成功。真实 Gateway、MySQL、MinIO、ClamAV 故障与恢复验证通过，最终业务行、审计、对象、ClamAV 容器、端口和 JVM 零残留。当前定向门禁为 `platform-common` 14 tests、`chat-service` 42 tests。详见 [M8 第七批：聊天附件隔离、恶意文件扫描与审计恢复](docs/62-m8-chat-malware-scan-and-quarantine.md)。

M8 第八批完成可靠通知投递：新增 `notification-service`（18109），消费支付成功、退款成功、发货和签收事实，在同一 MySQL 事务写消费幂等、通知任务、站内信和可选邮件任务。邮件通过数据库租约在事务外发送；SMTP 故障有限重试后进入 `NEEDS_ATTENTION`，顾客不能恢复，管理员只能用幂等、带原因和追加审计的命令重置为 `RETRY`。真实 Gateway、MySQL、Nacos、RocketMQ 和本地 SMTP 故障/恢复验证覆盖重复事件收敛、稳定 `Message-ID`、毒消息治理、原始载荷隐藏及最终零残留。详见 [M8 第八批：可靠通知投递与审计恢复](docs/63-m8-notification-reliable-delivery.md)。

M8 第九批完成 Fulfillment 物流 GEO：追加式 `logistics_trace` 与 MySQL 最新位置投影在同一本地事务提交，MySQL 8.4 通过 `POINT SRID 4326`、空间索引和 `ST_Distance_Sphere` 裁决附近查询；Redis GEO 只做提交后可丢失、可重建加速，缺失或异常时回退 MySQL 并尝试读修复。真实 MySQL/Redis 验证覆盖乱序轨迹不覆盖新位置、顾客所有权隔离、缓存删除与暂停回退、管理员重建及最终零残留。前端只展示真实坐标事实，不冒充外部地图或实时 GPS。详见 [M8 第九批：Fulfillment 物流 GEO 与可重建 Redis 投影](docs/64-m8-fulfillment-geo.md)。

M8 第十批完成商品评价闭环：Trade 在订单签收完成后通过 Outbox 发布带不可变订单行快照的 `OrderCompleted`，Catalog 幂等生成所有者隔离的评价资格；顾客可提交评价、点赞和举报，平台可幂等回复并审核隐藏，公开评分汇总只统计 `PUBLISHED`。真实 MySQL 8.4 验证发现并修复了 REPEATABLE READ 下并发幂等重试误报 409 的快照竞态，最终 8 路重试全部返回同一评价 ID。RocketMQ Proxy 停机时 Outbox 保持 `PENDING`，恢复后发布和资格消费收敛；临时 schema、Topic、消费组、端口和 JVM 均无残留。详见 [M8 第十批：商品评价、并发幂等与审核治理](docs/65-m8-product-reviews.md)。

M8 第十一批完成商品搜索闭环：Catalog MySQL 是商品最终事实，同事务搜索 Outbox 通过租约和 `external_gte` 投影到按需 OpenSearch；索引故障时公开接口明确返回 `MYSQL_FALLBACK/degraded=true`，持续失败进入 `NEEDS_ATTENTION` 并通过幂等审计命令恢复。蓝绿全量重建、原子别名切换、`MISSING/STALE/ORPHAN` 对账修复、下架隔离和前端降级提示均已落地。真实脚本完整执行 OpenSearch 停机/恢复、两商品重建、三类偏差注入与收敛，最终临时 schema、授权、索引、容器、端口和 JVM 均无残留，七个核心容器保持运行。详见 [M8 第十一批：商品搜索、可重建索引与事实对账](docs/66-m8-catalog-search.md)。

M8 第十二批完成运营统计独立闭环：新增 `analytics-service`（18110），只消费 Trade/Payment 六类版本化事件，在自有 MySQL 保存来源事件日志、日汇总、商品汇总、消费失败和重建审计；重复事件不重复累计，逻辑身份冲突进入治理。真实 RocketMQ Proxy 停机/恢复、旧事件收入覆盖边界、三类投影偏差、幂等审计重建、专用 Prometheus 身份和最终零残留均已通过。全量冷构建还发现并修复 Chat Outbox 纳秒时间写入 `TIMESTAMP(3)` 时的立即发布竞态。详见 [M8 第十二批：运营统计事件读模型、对账与审计重建](docs/67-m8-operational-analytics.md)。

2026-07-25 的 M8 阶段收口是历史交付快照；2026-07-28 又在当时的工作树上完成更严格的 M0–M8 三层证据复审。当时除 435 个后端测试、106 个前端 Vitest、7 个 E2E、PMD 与 SpotBugs 外，还验证了 48 个 PowerShell AST、4 个 MJS 语法、78 个 Markdown 相对链接、8 组有效 Compose 组合和 `git diff --check`；当前增量门禁数字以本文开头的 2026-08-01 记录为准。复审识别出旧 stable Trade 与当前候选对 `PaymentSucceeded` 的信封兼容但工作流语义不等价：HTTP 可按已验证顺序滚动，消息消费者不能新旧混跑，必须停旧消费者、让 RocketMQ 缓冲、再只启动候选消费者；启用新工作流后的回滚需要显式恢复。原 M9 已退出当前仓库范围，相关历史报告保留原命名作为时间线证据。详见 [M8 全面审查历史快照](docs/68-m8-full-audit-and-graduation-20260725.md)和 [M0–M8 三层证据审查](docs/69-m0-m8-pre-m9-three-layer-audit-20260728.md)。

## 项目与设计基线

- [素简记商城平台设计与实施计划书](素简记商城平台设计与实施计划书.md)
- [青荷默认主题优化](docs/70-qinghe-default-theme-20260728.md)
- [前端低耦合分层第一批](docs/71-frontend-layered-architecture-first-slice-20260728.md)
- [前端低耦合分层第二批：Catalog 公开浏览链](docs/72-frontend-catalog-layering-second-slice-20260728.md)
- [前端低耦合分层第三批：顾客会话与账户边界](docs/73-frontend-customer-session-layering-third-slice-20260730.md)
- [前端低耦合分层第四批：地址实体、所有者隔离与账户页面](docs/74-frontend-address-layering-fourth-slice-20260730.md)
- [前端低耦合分层第五批：购物袋、账户购物车与合并边界](docs/75-frontend-shopping-bag-layering-fifth-slice-20260730.md)
- [前端低耦合分层第六批：权威结算、竞态隔离与权限纵深](docs/76-frontend-checkout-layering-sixth-slice-20260730.md)
- [前端低耦合分层第七批：订单、支付与结果未知恢复](docs/77-frontend-order-payment-layering-seventh-slice-20260730.md)
- [前端低耦合分层第八批：Fulfillment 积木与真实签收恢复](docs/78-frontend-fulfillment-layering-eighth-slice-20260730.md)
- [前端低耦合分层第九批：售后三域事实与寄回竞态治理](docs/79-frontend-after-sale-layering-ninth-slice-20260801.md)
- [前端低耦合分层第十批：评价事实、参与竞态与结果未知恢复](docs/80-frontend-review-layering-tenth-slice-20260801.md)
- [前端视觉重构总计划](docs/81-frontend-visual-reconstruction-master-plan-20260801.md)
- [前端视觉重构 V1 审计与冻结报告](docs/82-frontend-visual-v1-audit-20260801.md)
- [前端视觉 V2 设计系统与全局壳层](docs/84-frontend-visual-v2-design-system-and-shell-20260802.md)
- [前端视觉 V3 三个真实原型](docs/85-frontend-visual-v3-three-prototypes-20260802.md)
- [前端视觉 V4 商品发现链](docs/86-frontend-visual-v4-product-discovery-20260802.md)
- [前端视觉 V5.1 购物袋与结算](docs/87-frontend-visual-v5-1-bag-checkout-20260802.md)
- [前端视觉 V5.2 订单、支付与履约](docs/88-frontend-visual-v5-2-order-payment-fulfillment-20260802.md)
- [前端视觉 V5.3 售后与退款](docs/89-frontend-visual-v5-3-after-sale-refund-20260802.md)
- [前端视觉 V6.1 身份与账户](docs/90-frontend-visual-v6-1-identity-account-20260802.md)
- [前端视觉 V6.2 地址与权益](docs/91-frontend-visual-v6-2-address-benefits-20260802.md)
- [前端视觉 V6.3 通知与 Chat](docs/92-frontend-visual-v6-3-notification-chat-20260803.md)
- [前端视觉 V6.4 管理端代表页与工作区](docs/93-frontend-visual-v6-4-1-governance-20260803.md)
- [前端视觉 V6.4.4 管理首页与 V6 收口](docs/101-frontend-visual-v6-4-4-operations-home-20260803.md)
- [前端视觉 V7.1 全站交付审计与冻结](docs/102-frontend-visual-v7-1-delivery-audit-20260803.md)
- [前端视觉 V7.2 响应式图片交付](docs/103-frontend-visual-v7-2-image-delivery-20260803.md)
- [前端视觉 V7.3 演示夹具与生产静态交付](docs/104-frontend-visual-v7-3-demo-static-deployment-20260803.md)
- [前端视觉 V7.4 GitHub 展示与发布候选](docs/105-frontend-visual-v7-4-release-candidate-20260803.md)
- [项目计划书](docs/00-project-master-plan.md)
- [产品范围](docs/01-product-scope.md)
- [服务架构](docs/02-service-architecture.md)
- [核心状态机](docs/03-core-state-machines.md)
- [数据所有权](docs/04-data-ownership.md)
- [分布式一致性策略](docs/05-consistency-strategy.md)
- [版本矩阵](docs/06-version-matrix.md)
- [本地开发网络](docs/07-local-development-network.md)
- [身份与令牌安全](docs/08-identity-security.md)
- [Redis 流量保护与降级](docs/09-redis-traffic-protection.md)
- [Catalog 服务](docs/10-catalog-service.md)
- [Inventory 服务](docs/11-inventory-service.md)
- [Trade 服务](docs/12-trade-service.md)
- [Payment 服务](docs/13-payment-service.md)
- [Fulfillment 服务](docs/14-fulfillment-service.md)
- [Marketing 服务](docs/15-marketing-service.md)
- [整单售后与退款](docs/16-after-sale-refund.md)
- [技术采纳矩阵与单机实验边界](docs/17-technology-adoption-matrix.md)
- [指标采集、看板与告警](docs/18-observability-and-alerting.md)
- [领域授权补偿与审计](docs/19-compensation-governance.md)
- [Payment 支付与退款对账](docs/20-payment-reconciliation.md)
- [Inventory 库存与退货回补对账](docs/21-inventory-reconciliation.md)
- [关键同步调用韧性](docs/22-synchronous-call-resilience.md)
- [Trade 订单恢复调度隔离](docs/23-trade-scheduling-isolation.md)
- [Payment 到 Trade 分布式追踪代表链路](docs/24-distributed-tracing.md)
- [Trade 与 Fulfillment 所有者域对账](docs/25-trade-fulfillment-reconciliation.md)
- [M2 毕业与容量准入报告](docs/26-m2-graduation-and-capacity-admission.md)
- [M3 Trade Outbox 多实例抢占与租约](docs/27-m3-trade-outbox-multi-instance.md)
- [M3 Trade 容器多实例与优雅停机](docs/28-m3-trade-container-multi-instance.md)
- [M3 消费者竞争、进程终止与发布治理](docs/29-m3-consumer-fault-and-release-governance.md)
- [M3 Inventory 预占结果未知恢复](docs/30-m3-inventory-unknown-result-recovery.md)
- [M3 双版本兼容、滚动发布与容量复测](docs/31-m3-dual-version-and-capacity.md)
- [M4 前端就绪与架构基线](docs/32-m4-frontend-readiness-and-architecture.md)
- [全项目审查与质量门禁](docs/33-project-wide-audit-and-quality-gate.md)
- [M4 身份会话与游客购物袋合并](docs/34-m4-identity-session-and-cart-merge.md)
- [M4 地址管理与只读结算试算](docs/35-m4-address-and-checkout-preview.md)
- [M4 权威结算、幂等下单与订单恢复](docs/36-m4-authoritative-checkout-and-order-recovery.md)
- [M4 订单中心与取消结果未知恢复](docs/37-m4-order-center-and-cancellation-recovery.md)
- [M4 Payment 与结果未知恢复](docs/38-m4-payment-and-unknown-result-recovery.md)
- [M4 履约与物流时间线](docs/39-m4-fulfillment-and-logistics-timeline.md)
- [M4 顾客售后、管理端与毕业报告](docs/40-m4-customer-after-sale-admin-and-graduation.md)
- [M5 容量方法与第一批基线](docs/41-m5-capacity-methodology-and-first-baseline.md)
- [M5 查询容量、订单分页与 N+1 收敛](docs/42-m5-query-capacity-and-order-pagination.md)
- [M5 写链容量与 Catalog 多级缓存](docs/43-m5-write-capacity-and-catalog-cache.md)
- [M0–M5 全量回归与毕业收口](docs/44-m0-m5-full-regression-20260721.md)
- [M5.5 仓库治理与进入下一阶段门禁](docs/45-m5-5-repository-governance.md)
- [M6 第一批：秒杀活动准入基线](docs/46-m6-flash-sale-admission-baseline.md)
- [M6 秒杀排队、最终裁决与毕业报告](docs/47-m6-flash-sale-queue-and-graduation.md)
- [M0–M6 全量回归与毕业收口](docs/48-m0-m6-full-regression-20260722.md)
- [M7 第一批：规模数据、查询基线与游标分页](docs/49-m7-scale-data-and-cursor-pagination.md)
- [M7 第二批：分布式 ID 与节点租约](docs/50-m7-distributed-id.md)
- [M7 第三批：Catalog 真实 MySQL 读副本](docs/51-m7-catalog-read-replica.md)
- [M7 第四批：Trade 两分片代表实现](docs/52-m7-trade-sharding.md)
- [M7 第五批：Trade 历史归档迁移、校验与回滚](docs/53-m7-trade-history-archive-migration.md)
- [M7 第六批：Trade 主动 2→4 重分片](docs/54-m7-trade-active-resharding.md)
- [M0–M7 全面审查与回归门禁](docs/55-m0-m7-full-audit-and-regression-20260723.md)
- [M8 第一批：可靠聊天持久化与客户端幂等](docs/56-m8-chat-reliable-persistence.md)
- [M8 第二批：聊天实时路由、跨节点投递与离线回放](docs/57-m8-chat-realtime-routing.md)
- [M8 第三批：聊天附件存储、完整性与授权下载](docs/58-m8-chat-attachment-storage-and-authorization.md)
- [M8 第四批：浏览器 WebSocket 短期握手票据](docs/59-m8-chat-browser-websocket-ticket.md)
- [M8 第五批：Chat 持久化消费失败治理](docs/60-m8-chat-consumer-failure-governance.md)
- [M8 全面审查、回归与毕业收口](docs/68-m8-full-audit-and-graduation-20260725.md)
- [M8 第六批：顾客端与客服端 Chat 会话工作区](docs/61-m8-chat-frontend-workspace.md)
- [M8 第七批：聊天附件隔离、恶意文件扫描与审计恢复](docs/62-m8-chat-malware-scan-and-quarantine.md)
- [M8 第八批：可靠通知投递与审计恢复](docs/63-m8-notification-reliable-delivery.md)
- [M8 第九批：Fulfillment 物流 GEO 与可重建 Redis 投影](docs/64-m8-fulfillment-geo.md)
- [M8 第十批：商品评价、并发幂等与审核治理](docs/65-m8-product-reviews.md)
- [M8 第十一批：商品搜索、可重建索引与事实对账](docs/66-m8-catalog-search.md)

## 本地中间件

参见 [deploy/docker/README.md](deploy/docker/README.md)。运行数据保存在 `D:/Middleware/PlainJournal`，本地凭据不进入版本库。

在搭建或升级业务服务前，可运行 [`poc/middleware-compatibility`](poc/middleware-compatibility) 中的真实中间件兼容性验证。

## 后端基础

多模块构建、服务端口和真实 Nacos 服务发现冒烟说明参见 [backend/README.md](backend/README.md)。

## 许可证

本项目采用 [Apache License 2.0](LICENSE)。
