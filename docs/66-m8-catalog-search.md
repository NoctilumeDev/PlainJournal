# M8 第十一批：商品搜索、可重建索引与事实对账

## 1. 批次结论

M8.11 已完成 Catalog 商品搜索闭环：

- Catalog MySQL 继续保存商品最终事实，OpenSearch 只保存可丢失、可重建的搜索投影；
- 商品新增、修改、上架和下架在本地事务中更新 `search_revision` 并写入搜索 Outbox，
  事务内不访问 OpenSearch；
- 投影任务使用数据库租约抢占、有限重试和 `external_gte` 外部版本，旧任务不能覆盖
  新商品版本；
- OpenSearch 不可用时，公开接口明确返回
  `source=MYSQL_FALLBACK / degraded=true`，不伪造正常索引搜索；
- 持续失败进入 `NEEDS_ATTENTION`，管理员只能使用幂等、带原因和追加审计的恢复命令
  重新进入投影，不能直接标记成功；
- 全量重建写入新物理索引，完成后原子切换别名，并删除 Catalog 自有旧索引；
- 对账识别 `MISSING / STALE / ORPHAN`，修复动作只新增 Outbox，不直接把数据库事实
  改成索引内容；
- 顾客端搜索页使用专用接口，展示降级提示，并阻止慢旧请求覆盖新查询结果；
- 按需 OpenSearch Profile、自动化测试、真实故障验证、静态分析和最终资源清理均已
  通过。

本批没有实现拼写纠错、同义词运营、中文分词插件、个性化排序、推荐系统、
搜索行为分析或 OpenSearch 集群高可用。在本批交付时，运营统计仍是 M8 的最后
一个独立闭环。

## 2. 事实与投影边界

```text
Catalog 管理写请求
        |
        v
product_spu / product_sku
+ search_revision
+ catalog_search_outbox
        | 同一 MySQL 本地事务
        v
Search Projection Job
        |
        | external_gte
        v
OpenSearch alias -> physical index
        |
        v
公开搜索先取得 ID，再回读 MySQL ACTIVE 商品
```

正确性边界：

1. 商品标题、状态、价格、分类、品牌和 SKU 仍由 Catalog MySQL 裁决。
2. OpenSearch 文档可以删除后重建，不能反向覆盖 MySQL。
3. 搜索 Outbox 状态更新、租约和审计全部保存在 Catalog schema。
4. 投影成功必须得到 OpenSearch 明确成功响应；超时或连接失败保持待处理或人工关注。
5. 搜索命中的商品 ID 必须回读 MySQL，只向公众展示当前仍为 `ACTIVE` 的商品。
6. 下架事务产生更高 `search_revision`，投影执行带外部版本的删除；即使索引短暂滞后，
   MySQL 回读仍阻止下架商品公开展示。

## 3. 数据与状态

Catalog Flyway `V5__create_search_projection_governance.sql` 新增或调整：

| 事实 | 用途 |
| --- | --- |
| `product_spu.search_revision` | 每个商品的权威搜索版本 |
| `catalog_search_outbox` | 增量投影、租约、有限重试和终态失败 |
| `catalog_search_recovery_audit` | Outbox 人工恢复的幂等命令与追加审计 |
| `catalog_search_rebuild` | 蓝绿全量重建任务、租约、进度和失败摘要 |
| `catalog_search_rebuild_recovery_audit` | 重建人工恢复审计 |

重建提交、投影恢复和重建恢复都把 `commandId + requestHash` 作为数据库命令事实：
并发同键同参数返回同一结果，同键异参数返回 `IDEMPOTENCY_CONFLICT`。恢复命令先通过
唯一审计事实占位，再改变 `NEEDS_ATTENTION` 目标状态，因此同一命令即使并发指向两个
不同目标，也最多推进一个目标，不会出现“两个目标已修改、最后审计唯一键才冲突”。
| `catalog_search_reconciliation` | `MISSING / STALE / ORPHAN` 问题生命周期 |

状态机：

```text
search outbox:
PENDING -> PROJECTING -> PUBLISHED
               \-----> NEEDS_ATTENTION

search rebuild:
PENDING -> RUNNING -> SUCCEEDED
             \----> NEEDS_ATTENTION

reconciliation:
OPEN -> RESOLVED
```

Outbox 与重建租约都记录 `claim_owner / claim_until`。进程退出后，其他实例只能在租约
过期后重新抢占；失败摘要保留最深层 root cause，避免只记录包装异常。

## 4. 搜索、重建与对账算法

公开搜索：

- 使用 `multi_match` 检索标题、副标题、描述、分类、品牌、SKU 名称和规格；
- 可按 `categoryId` 过滤；
- OpenSearch 返回商品 ID 和命中总数，Catalog 再按命中顺序回读 MySQL；
- `page * size` 结果窗口统一限制为 10,000；即使关闭 OpenSearch 或进入 MySQL 降级，
  也不能绕过深分页边界；
- OpenSearch 禁用或不可用时，使用现有 MySQL 基础关键词查询并显式标记降级。

增量投影：

- 上架商品写入文档，下架商品删除文档；
- 写入和删除都使用 `version_type=external_gte`；
- 同一商品的旧 Outbox 即使晚执行，也不能把新索引内容降级为旧版本。

蓝绿重建：

1. 生成运行任务专属物理索引；
2. 按商品 ID keyset 分批读取全部 `ACTIVE` 商品；
3. 批量写入目标索引；
4. 原子执行别名切换；
5. 对账确认后删除 Catalog 自有旧物理索引。

对账：

- MySQL 有、索引无：`MISSING`；
- 两侧都有但 revision 不同：`STALE`；
- 索引有、MySQL 当前无有效商品：`ORPHAN`；
- 修复只新增带目标版本的 Outbox；
- OpenSearch 版本扫描每页最多 1,000 条并使用 `search_after`，不触发 10,000 结果
  窗口；
- 任一侧达到扫描上限时，只比较两侧公共完整区间，不把未扫描尾部误判为
  `ORPHAN`；饱和时也不自动关闭超出公共区间的历史问题。

## 5. 接口与权限

| Method | Path | 权限 | 语义 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/catalog/search/products` | 公开 | 搜索商品并返回来源与降级标记 |
| `GET` | `/api/v1/catalog/admin/search/outbox` | `ADMIN/OPERATOR` | 查询投影状态 |
| `POST` | `/api/v1/catalog/admin/search/outbox/{id}/recover` | `ADMIN/OPERATOR` | 幂等审计恢复 Outbox |
| `POST` | `/api/v1/catalog/admin/search/rebuilds` | `ADMIN/OPERATOR` | 幂等提交蓝绿重建 |
| `GET` | `/api/v1/catalog/admin/search/rebuilds/{id}` | `ADMIN/OPERATOR` | 查询重建进度 |
| `POST` | `/api/v1/catalog/admin/search/rebuilds/{id}/recover` | `ADMIN/OPERATOR` | 幂等审计恢复重建 |
| `POST` | `/api/v1/catalog/admin/search/reconciliation` | `ADMIN/OPERATOR` | 只读或修复对账 |
| `GET` | `/api/v1/catalog/admin/search/reconciliation/issues` | `ADMIN/OPERATOR` | 查询问题生命周期 |

恢复和重建命令使用稳定 `commandId + requestHash`。同命令同载荷返回原结果，
同命令异载荷拒绝，原因不能为空且必须进入追加审计。

## 6. 前端闭环

顾客端 `/search` 已接入专用 `ProductSearchPage`：

- URL 查询参数保存关键词，刷新和前进/后退可以恢复；
- 返回 `items / matchedTotal / source / degraded`；
- `MYSQL_FALLBACK` 时展示“当前来自商品事实库、排序与召回范围可能较窄”的明确提示；
- 每次查询递增本地请求序号，迟到的旧响应不能覆盖新关键词结果；
- 商品卡片继续使用 Catalog MySQL 回读后的公开商品结构。

本批没有增加管理端搜索治理工作区。治理接口和真实验证已经完成，后续若开放 UI，
必须继续保留恢复原因、幂等命令、问题状态和审计事实，不能实现成通用改表页面。

## 7. 真实 OpenSearch/MySQL 故障验证

命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
.\tools\verify-m8-catalog-search.ps1 -EvidenceDate 20260724
```

最终证据：

```text
backend/.run/m8-catalog-search-20260724055739e5c718/verification.json
backend/.run/m8-catalog-search-20260724055739e5c718/cleanup.json
```

九阶段验证结果：

1. 网络诊断、Docker、七个核心中间件和按需 OpenSearch 通过；
2. Catalog 构建成功，创建隔离真实 MySQL schema 并执行 Flyway；
3. 两个商品增量投影成功，公开搜索来源为 `OPENSEARCH`；
4. 停止 OpenSearch 后，MySQL 商品更新继续提交；公开搜索明确降级，Outbox 三次有限
   尝试后进入 `NEEDS_ATTENTION`；
5. 恢复 OpenSearch 后，管理员同一恢复命令重放只生成一条审计，Outbox 收敛为
   `PUBLISHED`；
6. 同一重建命令重放只产生一条任务，蓝绿重建成功并索引两个商品；
7. 人工注入一个 missing、一个 stale、一个 orphan，首次对账各识别一个问题，
   修复生成三个 Outbox，复查时开放问题为 0、三个问题转为 `RESOLVED`；
8. 下架商品后 MySQL 状态为 `INACTIVE`，索引文档为 404，公开结果为 0；
9. 最终未发布 Outbox 和 `NEEDS_ATTENTION` 均为 0，并通过专用
   `X-Metrics-Token` 抓取六类搜索 Prometheus 指标。

最终状态：

```json
{
  "activeProducts": 1,
  "inactiveProducts": 1,
  "unpublishedOutbox": 0,
  "needsAttentionOutbox": 0
}
```

清理证据：

```json
{
  "cleanupErrors": [],
  "residualDatabaseSchemas": 0,
  "residualDatabaseGrants": 0,
  "residualCatalogPorts": 0,
  "residualCatalogJvms": 0,
  "residualSearchIndices": [],
  "openSearchContainerPresent": false,
  "openSearchContainerRunning": false,
  "coreContainersRunning": 7
}
```

脚本结束后又独立复核了临时 schema、18102/19200 端口、Java 进程、运行前缀索引和
OpenSearch 容器，结果仍为 0；七个核心容器保持运行。

## 8. 自动化与静态门禁

定向门禁：

```text
platform-common   14 tests
catalog-service   31 tests
合计              45 tests
0 failures / 0 errors / 0 skipped
```

全量门禁：

```text
mvn clean verify
88 份 Surefire 报告
312 tests
0 failures / 0 errors / 0 skipped
```

静态门禁：

- PMD Maven Plugin 3.28.0 / PMD 7.17.0：全 Reactor 0 违规；
- Catalog SpotBugs 低阈值专项：25 条诊断，Priority 1 为 0、Priority 2 为 22、
  Priority 3 为 3；
- PMD 首轮发现并删除 1 个未使用 `HashMap` import；
- SpotBugs 首轮发现索引别名小写化依赖默认 Locale，已改为 `Locale.ROOT`；
- 剩余搜索专项告警属于 Spring 单例依赖注入触发的 `EI_EXPOSE_REP2` 结构性基线，
  没有 Priority 1。

前端在 Node.js 24.14.0 / pnpm 11.9.0 下执行 `pnpm check`：

```text
Foundation Vitest   35
Storefront Vitest   52
Admin Vitest         2
合计                89
Playwright E2E       6
```

两端类型检查、生产构建和 axe 可访问性门禁同时通过。

## 9. 单机资源与部署边界

OpenSearch 使用 Compose `m8-search` Profile：

- 镜像：`public.ecr.aws/opensearchproject/opensearch:3.7.0`；
- 单节点；
- JVM 堆：512 MiB；
- 容器内存上限：1408 MiB；
- 宿主机只绑定 `127.0.0.1:19200`；
- 本地一次性验证关闭 Security Plugin，不把该配置复制到生产；
- Catalog 使用 JDK `HttpClient`，不额外引入 OpenSearch Java SDK；
- 与观测栈、ClamAV、MySQL 副本和分片等重型 Profile 互斥运行。

这证明了可重建投影、故障降级、审计恢复和对账机制，不代表单节点 OpenSearch
具备生产高可用、容量或安全基线。

## 10. 当前坐标

本批交付时 M8.1–M8.11 已完成，运营统计和整体审查仍待后续完成。2026-07-25
当前阶段状态见
[M8 全面审查、回归与毕业收口](68-m8-full-audit-and-graduation-20260725.md)：
运营统计、整体回归和毕业收口均已完成，M8 已关闭；M9 三个商户与 Go 异构统计
服务的技术候选门禁已满足，但仍按用户要求冻结，必须等待用户复审后单独确认进入。
