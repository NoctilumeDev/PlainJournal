# M7 第三批：Catalog 真实 MySQL 读副本

## 1. 目标与范围

本批在 Catalog 公开查询上建立可关闭的真实 MySQL 读副本实验，验证：

- 公开分类、品牌、商品列表、游标列表和详情可显式路由到副本；
- 管理写命令、写事务内校验和回读、Flyway 永远使用主库；
- 复制暂停时不掩盖延迟，读己之写通过显式主库提示完成；
- 副本连接或查询失败时，只读操作最多重放一次到主库；
- 副本恢复后重新承接普通公开读取；
- Profile、进程、账号、探针和容器均可清理。

本批没有把所有 `@Transactional(readOnly = true)` 自动送往副本。只有公开
GET 入口显式声明 `@CatalogReplicaRead`；管理端只读校验、写事务内查询、
缓存后台重建和未标记调用默认使用主库，避免把事务注解误当成一致性策略。

## 2. 实现结构

### 2.1 数据源与事务

- `catalogPrimaryDataSource` 绑定原 `spring.datasource`，并使用
  `@FlywayDataSource` 固定 Flyway；
- `catalogReplicaDataSource` 只在
  `ecommerce.catalog.read-replica.enabled=true` 时创建；
- MyBatis 和事务管理器使用 `@Primary` 的 `CatalogRoutingDataSource`；
- 路由上下文由公开控制器入口在事务创建前建立；
- `X-Catalog-Read-Consistency: primary` 在整个请求内强制主库；
- 主库提示优先于嵌套副本偏好，作用域结束后清理 ThreadLocal。

该请求头是本阶段的显式机制入口，不等于允许公网客户端无限绕过副本。生产化
时应改为短期签名一致性令牌，或限制为可信管理/内部调用并纳入独立限流。

副本 Hikari 使用 750 ms 连接超时、0 最小空闲和 6 最大连接，并关闭启动
失败阻断。副本不可用不会阻止 Catalog 以主库模式启动。

### 2.2 故障回退

副本失败不能在已经绑定的 JDBC 连接下面偷偷换库。本批在公开只读入口外层
捕获连接类失败，等待原只读事务结束后，以强制主库上下文完整重放一次请求。
SQL 语法错误、业务异常和“副本尚未复制到该行”不会触发回退。

指标不使用商品 ID 等高基数标签：

```text
ecommerce.catalog.datasource.connection.attempts{target=primary|replica}
ecommerce.catalog.datasource.replica.connection.failures
ecommerce.catalog.datasource.replica.fallbacks
ecommerce.catalog.datasource.primary.hints
```

### 2.3 缓存边界

真实路由验证显式关闭 Catalog 两级缓存，防止 Caffeine/Redis 命中掩盖数据源
选择和复制延迟。正常运行时缓存命中不访问数据库；当前请求中的冷加载继承
公开读路由，逻辑过期的异步后台重建默认使用主库，以免把复制延迟再次写入
共享缓存。Redis 仍然不参与复制正确性。

## 3. 自动化测试

`CatalogReadReplicaIntegrationTest` 使用两个独立 H2 数据源验证：

- 无提示的公开 GET 读取副本；
- 主库提示读取主库；
- 直接调用应用服务不会仅因 `readOnly` 事务误走副本；
- 写事务及其父分类校验固定主库；
- Flyway 不对副本自动迁移；
- 关闭副本连接池后请求在主库成功重放；
- 所有公开 GET 都必须显式声明副本资格。

另有路由作用域和连接失败分类单元测试。最终执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn -pl services/catalog-service -am verify
mvn -pl services/catalog-service -am `
  org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn -q -pl services/catalog-service -am `
  com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  "-Dspotbugs.effort=Max" "-Dspotbugs.threshold=Low" `
  "-Dspotbugs.xmlOutput=true"
```

结果：

- 公共模块 14 项、Catalog 18 项，共 32 项测试通过；
- PMD 7.17.0：0 违规；
- SpotBugs 4.9.8：14 条 Priority 2/3 诊断，Priority 1 为 0，
  缺失分析类为 0；
- PowerShell Parser、Compose config 和 `git diff --check` 通过。

## 4. 真实副本拓扑

独立 Compose Profile：`m7-catalog-replica`。

| 项目 | 值 |
| --- | --- |
| 主库 | `plainjournal-mysql:3306` / 宿主机 `13306` |
| 副本 | `plainjournal-mysql-replica:3306` / 宿主机 `13316` |
| 复制范围 | `ecom_catalog` |
| 副本内存上限 | 512 MiB |
| 应用副本账号 | 与 Catalog 用户同名，仅授予 `SELECT` |
| 复制账号 | 专项随机密码账号，结束后删除 |
| 缓存 | 专项验证关闭 |

主库当前实际为 MySQL 8.4.10、ROW binlog、GTID 关闭。为了不重启或在线修改
现有核心主库，本机实验使用 `mysqldump --source-data=2` 得到一致快照与
binlog 文件/位置，再启动单库复制。该选择只适用于当前单机、一次性副本实验；
真实生产拓扑仍应使用 GTID 自动定位、复制 TLS、长期凭据管理和副本监控。

验证命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
./tools/verify-m7-catalog-read-replica.ps1
```

脚本先执行机器级网络预检，拒绝观测栈和分片容器并存，生成一致快照，初始化
副本，启动单个 Catalog JVM，然后依次执行复制暂停、恢复、容器停机、主库
回退和副本重启。默认结束时移除副本容器，但保留独立数据目录供下次复用。

## 5. 真实验证结果

最终证据：

```text
backend/.run/m7-catalog-read-replica-20260722-182909/verification.json
backend/.run/m7-catalog-read-replica-20260722-182909/mysql-replica.log
backend/.run/m7-catalog-read-replica-20260722-182909/mysql-replica-inspect.json
```

| 场景 | 结果 |
| --- | --- |
| 初始复制 | IO/SQL 线程均为 `Yes`，`Seconds_Behind_Source=0` |
| 暂停 SQL 线程 | 普通公开读看不到主库新增探针 |
| 主库提示 | 同一探针立即可见 |
| 恢复 SQL 线程 | 444 ms 后副本可见探针 |
| 副本停机 | 请求返回主库数据，fallback 指标增加 1 |
| 副本重启 | 11.907 s 恢复复制和公开读取 |
| 路由指标 | 副本连接尝试 2、主库提示 1 |
| 故障指标 | 副本连接失败 1、主库回退 1 |

宿主机空闲内存从 3.52 GiB 变为 3.20 GiB。运行期间主库约使用
758 MiB / 768 MiB，副本约使用 476–487 MiB / 512 MiB，没有 OOM。
这进一步确认读副本必须与观测栈、分片和规模数据实验按时间互斥，不能常驻。

最终清理结果：

- Catalog 验证端口 `18102` 已释放；
- 副本端口 `13316` 已释放；
- `plainjournal-mysql-replica` 容器不存在；
- 临时复制账号为 0；
- 主库探针行 0；
- 清理错误 0。

验证过程中还修复了四个脚本/环境问题：首次初始化不能预先开启
`super_read_only`、MySQL 8.4 复制密码最长 32 字符、PowerShell 标量查询
必须防止数组自动解包、应用清理必须同时核对启动 PID 和监听端口。

## 6. 当前边界与下一批

本批没有证明：

- 复制延迟可以被忽略或用固定 sleep 消除；
- 所有后台查询都适合走副本；
- 副本故障时任何异常都可以安全重试；
- 本机文件/位置复制等同于生产 GTID 高可用；
- 单副本可以承担自动故障转移或成为新的最终事实；
- Trade 两分片、历史归档和在线迁移已经完成。

Trade 两分片后续已按互斥 Profile 和相同业务契约完成真实正逆向闭环，见
[M7 第四批：Trade 两分片代表实现](52-m7-trade-sharding.md)。历史归档的
断点续传、数据指纹、切读门禁、回滚和重放也已在第五批完成，见
[M7 第五批：Trade 历史归档迁移、校验与回滚](53-m7-trade-history-archive-migration.md)。
M7 下一批只处理主动 2→4 重分片；该证据完成后 M7 才能关闭。
