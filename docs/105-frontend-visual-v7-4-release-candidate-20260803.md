# 前端视觉 V7.4：GitHub 展示与发布候选收口

> 日期：2026-08-03  
> 状态：完成；本地 `v1.0.0-rc.1`，尚未创建 GitHub Release  
> 范围：真实双 Nginx 镜像、OCI 元数据、缓存/404/代理、健康检查、标签回退、截图与 GitHub 材料  
> 硬边界：不启动 Java 或核心中间件，不擅自创建 LICENSE、提交、推送或发布

## 1. 结论

V7.4 已把前端从“生产代码和 Compose 已准备”推进为“本地发布候选已真实验证”：

- 顾客端与管理端镜像均从仓库源码构建，不复制宿主机 `dist` 冒充容器构建；
- 两个镜像带版本、提交、构建时间、来源、标题和描述 OCI label；
- Nginx 真实响应证明入口、哈希资源、稳定图片和缺失资源采用不同合同；
- 同源 `/api` 真实转发到受控 Mock API，不进入 SPA fallback；
- 两个容器 HEALTHCHECK 均为 `healthy`；
- `rc.1 → rc.0 → rc.1` 三次切换均使用已存在镜像和
  `--no-build --pull never`，容器实际 image ID 随标签改变；
- README、三张截图、CHANGELOG、SECURITY 和人工 Release checklist 已完成；
- `LICENSE` 与 GitHub `origin` 仍由仓库所有者决定，当前没有伪造发布事实。

M0–M8 的后端交易、资金、库存、权益、权限和分布式正确性仍由既有三层证据裁决。
本批 Mock API 与静态容器只验证交付层，不替代真实 MySQL、Redis、Nacos、
RocketMQ、MinIO、ClamAV、SMTP 和 OpenSearch。

## 2. 单机互斥窗口

Docker 操作前执行：

```powershell
& 'D:\DevTools\Network\check-dev-network.ps1' -SkipDocker
```

结果：

| 项目 | 结果 |
| --- | ---: |
| 物理网卡 / IPv4 / 单默认路由 / 网关 | 通过 |
| Windows DNS / Maven Central 直连 | 通过 |
| Clash 监听与真实代理请求 | 通过 |
| TCP 动态端口 | 72 / 16384，0.4% |
| UDP 动态端口 | 11 / 16384，0.1% |
| 近 15 分钟 4231 / 4266 | 0 |

Docker Desktop 初始为关闭。本批从实际安装目录
`D:\DevTools\DockerDesktop` 启动；引擎就绪后运行容器数为 0，没有自动恢复七个
核心中间件，因此不需要停止有状态容器。整个 Docker 窗口没有并行运行 Java、压测、
全套中间件或另一组浏览器回归。

## 3. 镜像与 OCI 元数据

两个最终 target：

```text
storefront
admin
```

发布候选与回退基线：

| 镜像 | 标签 | Image ID |
| --- | --- | --- |
| Storefront | `v1.0.0-rc.1` | `sha256:416211da8f3...37edee2` |
| Storefront | `v1.0.0-rc.0` | `sha256:3d8b3668f968...7e34065` |
| Admin | `v1.0.0-rc.1` | `sha256:394c03d39aef...3e4f2218` |
| Admin | `v1.0.0-rc.0` | `sha256:9cf8dff89fac...35c973e9` |

候选 label：

```text
org.opencontainers.image.version  = v1.0.0-rc.1
org.opencontainers.image.revision = 11b252515020794fff1870bebef1d0e0ac44155e
org.opencontainers.image.source   = file:///C:/Users/lenovo/Desktop/PlainJournal/
```

仓库当前没有 `origin`，因此本地验证使用真实 `file://` 来源，不写虚假 GitHub URL。
正式发布必须在人工清单中把 source 改为真实远端仓库。

## 4. 真实 Nginx 合同

结构化验证文件：

```text
frontend/.run/v7-4-production-verification.json
```

该目录被 Git 忽略，只是本机运行证据；权威可重复合同保存在验证脚本和本报告。

| 项目 | 真实结果 |
| --- | --- |
| Storefront `/index.html` | `200`；`no-store, no-cache, must-revalidate` |
| Admin `/index.html` | `200`；`no-store, no-cache, must-revalidate` |
| `/assets/<hash>.js` | `public, max-age=31536000, immutable` |
| 稳定 AVIF 图片 | `public, max-age=86400, stale-while-revalidate=604800`；无 immutable |
| 缺失 `/assets/*.js` | `404` |
| 缺失 `/images/*.avif` | `404` |
| 同源 Catalog API | `200` |
| Storefront / Admin HEALTHCHECK | `healthy / healthy` |

候选、回退和恢复三轮中，四次容器 image ID 均与目标标签一致。验证脚本在失败时自动
清理；截图窗口使用 `-KeepRunning` 后又显式执行 `container:stop`。

## 5. 真实浏览器与截图

内置浏览器直接访问真实 Nginx：

| 页面 | 结果 |
| --- | --- |
| 顾客首页 | 单一 `main`、无横向溢出、Console warning/error 0、图片 AVIF |
| 商品详情 | 单一 `main`、无横向溢出、Console warning/error 0、800px AVIF |
| 管理治理 | 登录守卫恢复、单一 `main`、无横向溢出、Console warning/error 0 |

浏览器首次验收发现管理端顶栏仍显示 `M4 Admin`。根因是公开 Mock 夹具的
`displayName`，不是 Vue 模板。最终修正：

- 管理员改为“平台管理员”；
- 营销规则、物流单号、退款/支付/订单业务号移除 V/M 阶段标识；
- `check:delivery` 新增演示夹具“用户可见阶段标签”扫描。

最终截图：

- `docs/assets/v7-4/storefront-home.jpg`：97,780 bytes；
- `docs/assets/v7-4/storefront-product.jpg`：106,565 bytes；
- `docs/assets/v7-4/admin-governance.jpg`：88,242 bytes。

## 6. 首轮失败与修正

### 6.1 无 Git origin

Windows PowerShell 5 把 `git remote get-url origin` 的 stderr 提升为终止错误。
验证器改为先枚举 remote；只有存在 `origin` 才读取，否则使用真实 `file://` 来源。

### 6.2 容器构建缺少根 tsconfig

真实镜像首构建失败：

```text
Tsconfig not found /workspace/tsconfig.base.json
```

宿主机构建此前能读取该文件，因此静态门禁没有暴露问题。Dockerfile 现显式复制
`tsconfig.base.json`，两端镜像随后从源码构建成功。

### 6.3 同源 API 探测路径错误

验证器最初请求不存在的 `/api/v1/catalog/public/products/{id}`，真实返回 404。
没有放宽断言，而是按 Mock API 实际契约改为 `/api/v1/catalog/products/{id}`，
随后 Nginx 同源代理返回 200。

### 6.4 产品残留阶段标签

真实管理端页面暴露 `M4 Admin`。修复夹具并增加自动化扫描后，浏览器页面中的
M/V 阶段标签为 0。

## 7. 最终门禁

2026-08-03 最终 `pnpm check`：

| 门禁 | 结果 |
| --- | ---: |
| 单元 / 契约测试 | 303 / 303 |
| 开发态 Playwright E2E | 60 / 60 |
| 生产构建 E2E | 3 / 3 |
| 分层规则 | 28 / 28 |
| 交付审计 | 3 / 3 |
| 生产部署规则 | 3 / 3 |
| 发布材料规则 | 3 / 3 |
| 类型检查 | 顾客端 / 管理端 / packages 全通过 |
| 生产构建 | 顾客端 / 管理端全通过 |

本批没有修改后端源码，因此不重复消耗机器资源运行 Maven 全量；后端最近冻结基线仍为
435 tests、PMD 0 违规、SpotBugs Priority 1 为 0。正式 GitHub `v1.0.0` 发布前，
人工清单仍要求从冻结提交再跑一次 `mvn clean verify` 与 `pnpm check`。

## 8. 环境恢复与发布边界

结束时：

- `18000/18090/18200/18201/18300/18301/4173` 无监听；
- 无 PlainJournal Mock、Vite、Playwright、Java 或前端容器；
- Compose 项目无运行容器；
- Docker Desktop 已使用 `docker desktop stop` 正常退出，恢复本批开始前状态；
- 未 reset、checkout、clean、提交、推送、创建标签或 GitHub Release。

当前仓库达到本地 `v1.0.0-rc.1`。下一步不是继续扩大功能，而是仓库所有者：

1. 选择并审阅 LICENSE；
2. 配置正确 GitHub origin；
3. 从冻结提交执行发布清单；
4. 创建正式 `v1.0.0` 标签与 Release。

多商户、分账和 Go 异构服务仍转入未来独立《素简记 Pro》。
