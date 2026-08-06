# SpotBugs 基线与分类策略

## 当前基线

最近一次全 Reactor 低阈值扫描：

| 优先级 | 数量 | 门禁解释 |
| --- | ---: | --- |
| Priority 1 | 0 | 任何新增项都阻塞发布 |
| Priority 2 | 247 | 已公开的待分类基线，不等同于 247 个已确认缺陷 |
| Priority 3 | 66 | 低优先级审查基线 |
| 缺失分析类 | 0 | 扫描输入完整 |

SpotBugs 的数量是 bug instance 数，不是用户可见 Issue 数。框架生命周期、Spring
注入、序列化 DTO、空值模型和测试辅助代码可能产生重复或上下文相关诊断；但不能因此
把全部结果直接称为误报。

## Pattern 分类结果

全量 XML 只包含四种 pattern：

| Pattern | 数量 | 分类 | 结论 |
| --- | ---: | --- | --- |
| `EI_EXPOSE_REP2` | 227 | Framework pattern / DTO | 210 条位于 Spring 标注的组件、服务、仓库或控制器；3 条位于 `@Bean` 创建的 Catalog 缓存对象；14 条是请求或观测 DTO 的列表字段 |
| `EI_EXPOSE_REP` | 20 | DTO / serialization | 观测快照、配置属性、应用命令和请求 DTO 的列表 accessor；应用命令和配置列表已使用 `List.copyOf`、`Stream.toList()` 或等价不可变快照 |
| `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION` | 56 | Framework pattern | RocketMQ、WebSocket、关闭钩子和受检函数式接口需要保留框架契约，调用边界负责记录、重试或转入人工处理 |
| `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` | 10 | Accepted risk | 缓存装载、Outbox、附件确认、搜索切换和结果未知恢复需要让事务或调度边界感知失败，禁止静默吞掉 |

本轮没有 `Confirmed defect`、Priority 1、缺失分析类或分析错误。该结论不是把 313 条
统一标成误报，而是按完整 pattern 集合、类归属和对应源代码边界分类。模块与类级机器
可读分组见 [SpotBugs JSON 摘要](spotbugs-summary.json)。

## 模块分布

| 模块 | P1 | P2 | P3 | 合计 |
| --- | ---: | ---: | ---: | ---: |
| `platform-common` | 0 | 6 | 2 | 8 |
| `ecommerce-gateway` | 0 | 1 | 0 | 1 |
| `identity-service` | 0 | 6 | 0 | 6 |
| `catalog-service` | 0 | 28 | 4 | 32 |
| `inventory-service` | 0 | 24 | 8 | 32 |
| `trade-service` | 0 | 53 | 17 | 70 |
| `payment-service` | 0 | 24 | 6 | 30 |
| `fulfillment-service` | 0 | 22 | 8 | 30 |
| `marketing-service` | 0 | 37 | 6 | 43 |
| `chat-service` | 0 | 36 | 10 | 46 |
| `notification-service` | 0 | 5 | 2 | 7 |
| `analytics-service` | 0 | 5 | 3 | 8 |

## 分类规则

后续新增诊断必须进入以下一种状态：

1. **Confirmed defect**：存在真实错误路径，建立 Issue 并修复；
2. **Framework pattern**：由框架生命周期或强制接口契约触发，记录适用边界；
3. **DTO / serialization**：请求、响应、配置或不可变数据载体边界；
4. **Accepted risk**：有明确前置条件、影响和替代控制；
5. **Needs investigation**：证据不足，不允许静默删除；
6. **Fixed**：修复后由下一次报告证明消失。

禁止为了降低数字批量添加 `@SuppressFBWarnings`。抑制必须指向具体 bug pattern，
并在相邻注释或本台账中说明理由。

## 发布门禁

- Priority 1 必须保持为 0；
- 新增或数量上升的 Priority 2 必须先分类；
- 已知基线不能被 README 隐藏，当前数字由验证摘要统一展示；
- SpotBugs 不绑定普通 `mvn verify`，避免每次快速回归承担全量分析成本；
- 正式发布和重要后端改动需重新生成 12 份报告，并保存 Actions 或 Release 证据。

重现命令：

```powershell
cd backend
./mvnw -DskipTests install
./mvnw com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  "-Dspotbugs.effort=Max" "-Dspotbugs.threshold=Low" `
  "-Dspotbugs.xmlOutput=true" "-DskipTests"
cd ..
./tools/summarize-spotbugs.ps1
```
