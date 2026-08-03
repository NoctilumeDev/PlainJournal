# M5.5 仓库治理与进入下一阶段门禁

> 审查日期：2026-07-22  
> 状态：已完成  
> 范围：目录结构、死代码与依赖、脚本和配置、文档一致性、后端与前端完整自动化门禁

## 1. 结论

M0–M5 当前工作树可以作为下一阶段基线。审查没有发现需要阻断后续开发的 P0/P1 代码问题，也没有发现可以脱离 Spring、MyBatis、反射、SPI 或业务状态机语义而机械删除的主代码。

本轮不使用最近提交代替当前事实，不执行 `reset`、`checkout`、`clean`、暂存或提交。审查开始时工作树包含 157 个修改项和 322 个未跟踪文件，均按用户现有成果处理。

## 2. 目录与产物分层

仓库源文件按以下边界继续维护：

- `backend/`：11 个 Maven Reactor 模块、八个应用、真实冒烟和 M3–M5 专项工具；
- `frontend/`：顾客端、管理端、共享 foundation 和 Playwright；
- `deploy/`：本地中间件、Nacos 配置和观测栈；
- `docs/`：总计划、领域设计、里程碑和正式验证报告；
- `poc/`：真实中间件兼容性 POC，不并入常驻业务服务。

生成物与证据的处理规则：

| 类型 | 处理 |
| --- | --- |
| `target/`、`dist/`、`coverage/`、`node_modules/` | 可再生构建产物，继续忽略 |
| `test-results/`、`playwright-report/` | Playwright 生成物，本轮补入 `.gitignore` |
| `backend/.run` 正式结果 | 保留，作为 M3–M5 可复验索引 |
| `backend/.run` 历史调试目录 | 不进入 Git；删除前必须先确认最终摘要和必要原始证据已归档 |
| `.run/observability-tools` | 本机工具缓存，不属于源码或业务证据 |

盘点时 `backend/.run` 约 937.46 MB，其中八份双版本实验源码快照约 799 MB；`.run/observability-tools` 约 668.68 MB。它们均已被忽略，不污染 Git 状态。正式保留索引仍以 `docs/44` 和各专题报告列出的最终 JSON、M5 正式目录为准。本轮没有为追求目录数字直接删除历史实验事实。

## 3. 死代码、依赖与重复代码审查

### 3.1 Java

- `mvn clean verify`：50 份 Surefire 报告、179 个测试，0 失败、0 错误、0 跳过；
- PMD 7.17.0：9 份报告、0 违规；
- SpotBugs 4.9.8：9 份报告、179 条 Rank 18/19，Priority 1 为 0；
- `mvn dependency:analyze` 已复核。Starter、自动配置、驱动、Flyway、Nacos、Actuator、Tracing、测试聚合依赖仍会产生框架型误报，没有发现新的可安全机械删除依赖。

宽泛异常主要位于 RocketMQ 消费/确认、Outbox 发布、MinIO SDK、追踪包装和关闭清理边界。这些位置需要把第三方异常转换为重试、失败台账或领域异常，不能只为静态数字批量收窄或吞掉异常。

### 3.2 TypeScript/Vue

三套前端工程在临时启用未使用符号诊断后全部通过。本轮将以下规则写入共享 `tsconfig.base.json`，后续 `pnpm check` 会持续阻止未使用局部变量和参数进入主干：

```json
"noUnusedLocals": true,
"noUnusedParameters": true
```

精确哈希发现的重复文件仅包括两端应用入口级配置、Pinia 实例、Vite 配置、公共 Catalog 只读适配器和服务测试占位配置。这些文件体量小、部署边界清晰；当前抽取为共享运行时模块会增加耦合，收益不足，因此不做过度抽象。

前端锁文件中的 `glob@8` / `inflight` 弃用提示来自 `@vue/test-utils -> js-beautify` 的传递开发依赖，不进入生产包；`pnpm audit` 无已知漏洞。

## 4. 脚本与本机运行时

22 个 PowerShell 脚本全部通过 Parser。

M5 查询容量和 Catalog 缓存脚本原先分别依赖裸 `node` 和硬编码 `D:\Node.js\current\node.exe`。本轮统一为：

1. 优先使用当前 `PATH` 解析到的 `node`；
2. 若 `PATH` 不可用，再回退到 `D:\Node.js\current\node.exe`；
3. 两者均不存在时明确失败，不伪造压测成功。

本机实际解析为 `D:\Node.js\current\node.exe` 24.14.0；pnpm 11.9.0 由用户级 shim 调用当前 `PATH` 中的 Node。`NODEJS_HOME` 未设置，`PATH` 中只有一个 Node 目录，没有双版本环境变量冲突。

M3/M4 故障脚本存在少量重复的 `.env` 解析、健康等待和代理请求辅助函数。它们当前是独立、可单文件复验的故障工具，且已形成正式证据；在没有专项回归覆盖共享模块加载失败、清理和退出码之前，不为减少行数集中重构。

## 5. 文档门禁

- README、总计划、版本矩阵、领域文档和 M0–M5 报告的当前数字保持一致；
- 历史专题中的阶段性测试数字继续保留，不回写成 179；
- Markdown 相对链接检查通过；
- M5.5 只记录仓库治理事实，不覆盖 `docs/44` 的真实中间件与并发证据。

## 6. 本轮最终门禁

2026-07-22 在修改后的工作树执行：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
mvn --% -q com.github.spotbugs:spotbugs-maven-plugin:4.9.8.2:spotbugs `
  -Dspotbugs.effort=Max -Dspotbugs.threshold=Low -Dspotbugs.xmlOutput=true

cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
pnpm audit --registry https://registry.npmjs.org
```

结果：

- 后端 179 个测试全部通过；
- PMD 0 违规，SpotBugs 0 条 Priority 1；
- 前端 70 个 Vitest、2 个 Playwright E2E、类型检查和两端生产构建全部通过；
- `pnpm audit` 无已知漏洞；
- Node 负载执行器语法和 22 个 PowerShell 脚本语法通过。

本轮没有修改业务 Java、Vue 业务流程、数据库迁移、消息契约、Nacos 或 Docker 配置，因此没有重复启动完整真实中间件烟测。真实 MySQL、Redis、Nacos、RocketMQ、MinIO、观测、多实例、故障和 1000/100 并发结论继续引用 2026-07-21 的 [M0–M5 全量回归与毕业收口](44-m0-m5-full-regression-20260721.md)，不把自动化门禁替代为新的真实中间件证据。

## 7. 下一阶段准入

M5.5 完成后，下一产品/工程阶段仍按总计划进入 M6“秒杀与峰值流量体系”。M9 的三个商户与 Go 异构统计服务继续等待 M6–M8 的进入条件，不提前把商户、租户和分账字段污染自营 B2C 主干。
