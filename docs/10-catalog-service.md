# 商品目录服务

## 1. 实现边界

`catalog-service` 拥有独立的 `ecom_catalog` schema，负责：

- 分类与品牌；
- SPU 商品信息；
- SKU 规格、销售价与划线价；
- 草稿、上架、下架状态；
- 私有 MinIO 商品图片引用与临时访问地址；
- 基于订单完成事件的评价资格、评价、评分汇总、点赞、平台回复、举报和审核。
- 基于 MySQL 搜索 Outbox、OpenSearch 外部版本、蓝绿索引重建和版本对账的商品搜索。

库存数量、库存预占和扣减不属于目录服务。`inventory-service` 以 SKU ID 为业务关联，
但不能直接写目录表。订单完成事实归 Trade；Catalog 只消费带不可变订单行快照的
`OrderCompleted`，不在评价请求中同步查询 Trade。

## 2. 状态和一致性

```text
DRAFT -> ACTIVE -> INACTIVE
           ^          |
           +----------+
```

- 新商品必须至少包含一个 SKU，并以 `DRAFT` 创建。
- 只有存在可用 SKU 的商品才能发布。
- 公开接口只返回 `ACTIVE` 商品，草稿和下架商品返回 `404`。
- SPU、SKU、分类和品牌包含 `version` 字段；管理修改采用 MyBatis-Plus 乐观锁。
- SPU 与初始 SKU 在同一个本地 MySQL 事务中写入。
- 商品创建使用 `operatorId + Idempotency-Key + requestHash` 建立命令身份；同键同载荷返回
  原商品，同键异载荷返回 `IDEMPOTENCY_CONFLICT`。管理端可按原键查询创建事实，网络
  UNKNOWN 后先查询、再原样重放，不能换键猜测重试。
- 媒体确认以唯一 `objectKey` 收敛；相同商品、SKU 和排序的重复确认返回原媒体，旧键
  不能被拿来改绑另一个商品或排序位置。
- 金额使用 MySQL `DECIMAL(18,2)` 和 Java `BigDecimal`；HTTP DTO 与应用服务都拒绝超过两位小数的价格，避免依赖数据库静默舍入。
- 商品详情两级缓存的 loader 只拥有它开始读取时那一代 cache-write authority；更新提交后的
  本地或跨实例失效会推进 generation，迟到 loader 可以完成原请求，但不能再把旧快照写回
  local/Redis。远端失效接收者也删除 Redis 键，避免在途写入夹在发布端 delete 与消息到达之间
  复活旧值。

评价状态：

```text
review_eligibility: ELIGIBLE -> REVIEWED
product_review:     PUBLISHED -> HIDDEN
review_report:      OPEN -> RESOLVED(UPHELD | REJECTED)
```

- `eventId + consumerGroup`、`orderNo + lineNo` 和评价资格唯一约束共同阻止重复资格；
- 评价提交使用 `userId + Idempotency-Key + requestHash`，同键同载荷返回原评价，同键
  异载荷返回 `IDEMPOTENCY_CONFLICT`；
- MySQL 资格行锁和唯一约束承担最终并发裁决；`createReview` 使用
  `READ_COMMITTED`，保证等待锁后的幂等复查看到已提交结果；
- 公开汇总只统计 `PUBLISHED`。举报成立时，评价隐藏、汇总扣减、举报解决和审核审计
  在同一本地事务提交。

搜索状态：

```text
search outbox: PENDING -> PROJECTING -> PUBLISHED
                              \-----> NEEDS_ATTENTION
rebuild:       PENDING -> RUNNING -> SUCCEEDED
                         \-------> NEEDS_ATTENTION
reconciliation: OPEN -> RESOLVED
```

- `product_spu.search_revision` 是商品搜索版本事实，OpenSearch 仅保存派生文档；
- 商品写事务只写 MySQL 和 `catalog_search_outbox`，事务内不访问索引；
- 增量投影使用 `external_gte`，较旧并发任务不能覆盖较新版本；
- 全量重建使用新物理索引和原子别名切换，成功后删除 Catalog 自有旧索引；
- 搜索结果回读 MySQL，只返回 `ACTIVE` 商品；索引不可用时返回
  `MYSQL_FALLBACK / degraded=true`；
- 对账自动修复只新增 Outbox。饱和扫描使用保守公共区间，禁止把未扫描尾部当作
  `ORPHAN` 删除。

## 3. 权限边界

目录服务复用身份服务签发的 HS256 JWT，并验证相同的 issuer。角色来自 `roles` claim：

| 接口 | 权限 |
| --- | --- |
| 分类、品牌、商品列表与详情 GET | 公开 |
| 商品搜索 GET | 公开；最多读取 OpenSearch 前 10,000 条结果窗口 |
| 商品评价列表与评分汇总 GET | 公开；登录用户额外获得自己的点赞状态 |
| 评价资格、提交、点赞和举报 | `CUSTOMER`，并按 JWT subject 隔离所有者 |
| `/api/v1/catalog/admin/reviews/**` | `ADMIN` 或 `OPERATOR` |
| `/api/v1/catalog/admin/**` | `ADMIN` 或 `OPERATOR` |

顾客持有合法 JWT 也不能写目录数据。目录服务独立执行授权，不依赖网关替它兜底。

## 4. 图片直传

```text
管理前端 -> catalog: 申请上传意图
catalog -> MinIO: 生成短期 PUT URL
管理前端 -> MinIO: 直接上传图片
管理前端 -> catalog: 确认对象键
catalog -> MinIO: stat 校验 MIME 与大小
catalog -> MySQL: 保存 product_media
```

允许 `JPEG`、`PNG`、`WebP`，单文件默认不超过 10 MB。Bucket 保持私有，数据库只保存对象键和元数据。公开读取时生成短期 GET URL；如果 MinIO 暂时不可用，商品文字、SKU 和价格仍返回，媒体 URL 为 `null`。

确认接口只接受当前商品前缀 `products/{spuId}/` 下的对象键，并校验可选 SKU 确实属于该 SPU，避免跨商品引用。

## 5. 主要接口

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/catalog/categories` | 活跃分类 |
| `GET` | `/api/v1/catalog/brands` | 活跃品牌 |
| `GET` | `/api/v1/catalog/products` | 已发布商品分页与筛选 |
| `GET` | `/api/v1/catalog/products/cursor` | 按 `createdAt + id` 游标读取已发布商品，不返回总数 |
| `GET` | `/api/v1/catalog/products/{id}` | 已发布商品详情 |
| `GET` | `/api/v1/catalog/search/products` | OpenSearch 商品搜索；故障时明确 MySQL 降级 |
| `POST` | `/api/v1/catalog/admin/categories` | 创建分类 |
| `POST` | `/api/v1/catalog/admin/brands` | 创建品牌 |
| `GET` | `/api/v1/catalog/admin/products` | 主库读取全部经营状态的商品分页 |
| `GET` | `/api/v1/catalog/admin/products/{id}` | 主库读取完整经营详情 |
| `GET` | `/api/v1/catalog/admin/products/by-idempotency-key/{key}` | 按当前操作员与原创建键查询事实 |
| `POST` | `/api/v1/catalog/admin/products` | 按 `Idempotency-Key` 创建 SPU 和初始 SKU |
| `PUT` | `/api/v1/catalog/admin/products/{id}` | 更新 SPU |
| `POST` | `/api/v1/catalog/admin/products/{id}/publish` | 上架 |
| `POST` | `/api/v1/catalog/admin/products/{id}/unpublish` | 下架 |
| `PUT` | `/api/v1/catalog/admin/products/{id}/skus/{skuId}` | 更新 SKU 与价格 |
| `POST` | `/api/v1/catalog/admin/products/{id}/media/upload-intents` | 申请 PUT URL |
| `POST` | `/api/v1/catalog/admin/products/{id}/media` | 确认媒体对象 |
| `GET` | `/api/v1/catalog/products/{id}/review-summary` | 公开评分汇总 |
| `GET` | `/api/v1/catalog/products/{id}/reviews` | 公开评价分页 |
| `GET` | `/api/v1/catalog/review-eligibilities` | 当前顾客评价资格 |
| `POST` | `/api/v1/catalog/reviews` | 按 `Idempotency-Key` 提交评价 |
| `POST/DELETE` | `/api/v1/catalog/reviews/{id}/likes` | 点赞或取消点赞 |
| `POST` | `/api/v1/catalog/reviews/{id}/reports` | 举报公开评价 |
| `GET` | `/api/v1/catalog/admin/reviews/reports` | 管理员举报列表 |
| `POST` | `/api/v1/catalog/admin/reviews/{id}/reply` | 幂等平台回复 |
| `POST` | `/api/v1/catalog/admin/reviews/reports/{id}/resolve` | 带原因审核举报 |
| `GET` | `/api/v1/catalog/admin/search/outbox` | 搜索 Outbox 状态 |
| `POST` | `/api/v1/catalog/admin/search/outbox/{id}/recover` | 幂等审计恢复搜索投影 |
| `POST/GET` | `/api/v1/catalog/admin/search/rebuilds` | 提交或查询蓝绿全量重建 |
| `POST` | `/api/v1/catalog/admin/search/rebuilds/{id}/recover` | 幂等审计恢复重建 |
| `POST` | `/api/v1/catalog/admin/search/reconciliation` | 执行只读或修复对账 |
| `GET` | `/api/v1/catalog/admin/search/reconciliation/issues` | 查询对账问题生命周期 |

## 6. 当前管理前端的目录边界

管理端 `/catalog` 已是 Catalog owner 商品经营工作区：

- 管理列表和详情使用员工 Bearer Token，从主库读取 `DRAFT / ACTIVE / INACTIVE`，不再
  用公开 ACTIVE 投影冒充经营事实；
- 页面支持创建含首个 SKU 的草稿、编辑 SPU、上下架、更新 SKU/价格，以及申请直传
  URL、上传并确认商品媒体；
- 64 位商品、SKU、分类、品牌和媒体 ID 在 JSON、Foundation、Pinia 和 Vue 中全程保持
  字符串；写命令提交当前 owner version，不让旧详情覆盖新事实；
- 商品创建在发送前把操作员、命令 ID 和完整载荷保存在本地。网络、超时、非法响应或
  5xx 后进入 `UNKNOWN`，先按原键查询服务端事实，只允许原样重放；
- 版本化 SPU 命令在 UNKNOWN 后读取 owner 详情，只有目标字段和下一 version 同时吻合才
  恢复为成功；否则保持 UNKNOWN，不自动重放业务写；
- 媒体确认 UNKNOWN 后按相同 object key 查询 owner 详情。确认接口本身幂等，避免响应
  丢失后写出第二条媒体记录；
- 503、超时或非法读取不会清空上一次已知事实；员工或会话 authority 切换后，旧读写
  结果都不得进入新会话。

分类和品牌在本切片只作为已有活跃事实供选择；其创建接口尚未扩成独立经营页面，也不
在这里伪造结果未知恢复。前端实现由真实浏览器验收与
[当前验证摘要](verification-summary.md)约束。

### 6.1 当前管理前端的评价治理边界

截至 V6.4.3，管理端 `/reviews` 已迁入独立 `admin-review` entity：

- `OPEN/RESOLVED` 举报按真实分页和状态读取，刷新失败保留上一次已知事实；
- report、review、product、SKU 和 reply 的 64 位 ID 全程保持字符串；
- 平台回复使用原 `Idempotency-Key`；举报审核使用原 `commandId`；
- 网络、超时、非法响应或 5xx 后冻结管理员、举报快照、命令 ID 和完整载荷，只允许
  原样重放；
- 举报列表不返回审核命令 ID/原因，公开评价也不能证明命令身份，因此二者不能单独
  把结果归因给原命令；
- `UPHELD` 与 `REJECTED` 是举报结论，`PUBLISHED/HIDDEN` 是评价可见性，前端不会
  把驳回举报解释为重新发布已隐藏评价；
- operator/token 切换会使旧请求响应失效，pending 命令按 operatorId 隔离。

前端实现和故障链由 E2E 与
[当前验证摘要](verification-summary.md)约束。

## 7. 公开读副本边界

M7 第三批为公开分类、品牌、商品列表、游标列表和详情增加显式副本资格。
路由不根据 `@Transactional(readOnly = true)` 自动猜测，因此管理端校验、
写事务内读取和 Flyway 固定使用主库。

需要立即读取刚完成的管理写结果时，调用公开 GET 可携带：

```text
X-Catalog-Read-Consistency: primary
```

普通公开读仍走副本。复制延迟导致的未命中不会自动回退；只有连接类故障才会
在原只读事务结束后向主库重放一次。真实副本 Profile 默认不常驻，并与观测栈、
分片和规模数据实验互斥。最终机制、故障和资源结论见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。

`primary` 是事实源要求，不只是 datasource route hint。商品详情收到该要求时必须绕过
不能证明新鲜度的 local/Redis cache，并在主库事务中重新加载；普通读的旧缓存存在不能
成为强读返回旧投影的理由。

公开评价列表和评分汇总也属于显式副本资格；评价资格、提交、点赞、举报、回复和
审核全部固定主库。副本延迟只能造成短暂展示滞后，不能改变评价资格或审核事实。

## 8. 验证方式

```powershell
cd backend
./mvnw.cmd clean verify
../deploy/docker/bootstrap-resources.ps1
./run-foundation-smoke.ps1
```

H2 集成测试覆盖权限、草稿隔离、管理读、创建幂等与按键恢复、发布、金额精度、乐观锁、
媒体重复确认、MinIO 读取降级，
评价资格幂等、所有者隔离、并发提交、汇总、点赞、回复、举报和审核状态，以及搜索
Outbox 状态、恢复审计、重建、对账、降级元数据、10,000 结果窗口和公开回读边界。
缓存单元测试另覆盖失效撤销在途 loader 写资格、远端失效删除两级缓存；路由集成测试
保留旧缓存前提并验证显式 `primary` 商品详情只读取主库事实。
OpenSearch 版本扫描另有 1,001 条文档拆为 1,000 + 1 两页并携带 `search_after` 的
HTTP 适配器测试。

M7 第一批在 10,000 SPU / 20,000 SKU 的 Small 数据上验证 offset 与 keyset 各
100 条结果完全一致。V3 删除旧冗余索引后，首屏、深 offset 和 keyset 均使用
`(status, category_id, created_at, id)` 覆盖索引且没有排序节点。关键词使用
`%keyword%` 时不宣称当前 B-Tree 具备同等收益。最终证据见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。

M7 第三批通过两个独立 H2 数据源覆盖路由边界，并使用真实 MySQL 8.4 主从
验证复制暂停、主库提示、444 ms 追平、副本停机回退和 11.907 秒恢复。

M8.10 在真实 MySQL 8.4、Nacos、RocketMQ、Gateway、Trade 和 Catalog 上验证
`ShipmentSigned -> OrderCompleted -> review_eligibility`、8 路相同提交收敛为一个
评价 ID、跨账户 404、点赞重放、平台回复、举报审核、评分扣减，以及 RocketMQ Proxy
停机时 Outbox 保持 `PENDING`、恢复后收敛。最终证据见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。

M8.11 在真实 MySQL 8.4、按需 OpenSearch 3.7.0 和独立 Catalog JVM 上验证两商品
增量投影、OpenSearch 停机时 MySQL 更新继续提交、公开查询明确
`MYSQL_FALLBACK/degraded=true`、三次有限重试后进入 `NEEDS_ATTENTION`、管理员
幂等审计恢复、蓝绿重建、missing/stale/orphan 注入与修复，以及下架商品索引删除
和公开隔离；临时 schema、授权、索引、OpenSearch 容器、18102/19200 端口和 JVM
残留均为 0。
最终证据见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。

阶段测试数字保留在对应归档报告；当前 Catalog 与全仓门禁统一见
[验证摘要](verification-summary.md)，不在领域文档重复维护。

评价图片/视频不在当前仓库范围，不能把现有商品媒体上传链路复用成未经内容授权和
审核的评价媒体链路。
