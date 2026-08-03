# 素简记前端静态部署

本目录把顾客端和管理端生产构建分别封装为 Nginx 镜像。两端使用同一套静态服务器
规则，但构建产物和发布标签独立。

## 1. 运行边界

- 顾客端：宿主机 `127.0.0.1:18300`；
- 管理端：宿主机 `127.0.0.1:18301`；
- 容器通过 `host.docker.internal:18000` 访问 Gateway；
- `/api/` 只代理 HTTP API；
- `/ws/` 保留 WebSocket Upgrade；
- 业务状态、鉴权与事实裁决仍由 Gateway 和所有者服务负责，Nginx 不伪造成功。

本机启动 Docker 前必须先阅读 `docs/07-local-development-network.md`。Docker Desktop
可能自动恢复七个核心中间件，因此前端静态镜像验证不能与全量浏览器、压测或另一套
项目并行执行。

## 2. 构建与启动

复制环境示例但不要提交真实仓库地址或凭据：

```powershell
Copy-Item .env.example .env
docker compose config
docker compose build storefront-web admin-web
docker compose up -d
```

正式标签必须同时写入 OCI 元数据：

```powershell
$env:PLAINJOURNAL_OCI_VERSION = 'v1.0.0'
$env:PLAINJOURNAL_OCI_REVISION = (git rev-parse HEAD)
$env:PLAINJOURNAL_OCI_CREATED = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
$env:PLAINJOURNAL_OCI_SOURCE = 'https://github.com/NoctilumeDev/PlainJournal'
```

V7.4 候选验证发生在远端仓库创建前，因此历史证据使用真实 `file://` 工作区来源；
正式 `v1.0.0` 镜像必须使用上面的公开仓库地址。

验证：

```powershell
curl.exe --noproxy '*' -I http://127.0.0.1:18300/
curl.exe --noproxy '*' -I http://127.0.0.1:18301/
```

停止只作用于本 Compose 项目：

```powershell
docker compose down
```

不使用 `down -v`，前端镜像没有理由删除其他项目卷或中间件数据。

## 3. History fallback 与缓存

Nginx 规则按资源类型分开：

| 路径 | 行为 |
| --- | --- |
| `/api/` | 转发 Gateway，不进入 SPA fallback |
| `/ws/` | 转发 Gateway 并升级 WebSocket |
| `/assets/` | 缺失返回 404；Vite 内容哈希资源缓存一年并标记 immutable |
| `/images/` | 缺失返回 404；稳定文件名只缓存一天并允许七天 stale-while-revalidate |
| `/index.html` | no-store，避免发布后旧入口长期驻留 |
| 其他前端路由 | `try_files ... /index.html`，支持 Vue Router History 刷新 |

稳定商品图片没有内容哈希，因此禁止套用 immutable。API、WebSocket、缺失 JS 和缺失
图片不能回退成 200 HTML。

## 4. 发布与回退

发布镜像必须使用不可变版本标签：

```powershell
$env:PLAINJOURNAL_IMAGE_PREFIX = 'registry.example/plainjournal'
$env:PLAINJOURNAL_FRONTEND_TAG = 'v1.0.0'
docker compose build storefront-web admin-web
docker compose push storefront-web admin-web
docker compose up -d
```

回退只切换到已经存在的旧标签，不在故障现场重新构建：

```powershell
$env:PLAINJOURNAL_IMAGE_PREFIX = 'registry.example/plainjournal'
$env:PLAINJOURNAL_FRONTEND_TAG = 'v0.9.1'
docker compose pull storefront-web admin-web
docker compose up -d --no-build --pull never
```

前端回退不回滚数据库迁移、消息版本或业务状态。若前端依赖了不再兼容的后端契约，
必须按对应发布文档处理，不能靠换静态文件掩盖契约不兼容。

## 5. 发布候选验证

独立 Docker 窗口中运行：

```powershell
pnpm container:verify
```

验证器会构建 `v1.0.0-rc.0` 和 `v1.0.0-rc.1` 两组镜像，检查 OCI label、
`Cache-Control`、missing asset/image 404、同源 API 和
`State.Health.Status`，然后执行：

```text
candidate → baseline → candidate
```

三次切换均使用本地已存在镜像和 `--no-build --pull never`，并核对容器实际
image ID。默认验证结束会关闭 Mock API 和双容器；只有采集截图时才使用脚本的
`-KeepRunning` 参数，完成后必须执行 `pnpm container:stop`。

## 6. 当前验证边界

仓库门禁会验证 Nginx location、缓存策略、Gateway/WebSocket 边界、两个镜像 target、
Compose 名称/端口/标签和回退说明。生产 `dist` 另由真实 Chromium 验证深层路由刷新、
同源 API 代理、登录拒绝和管理端守卫恢复。

2026-08-03 已在 Docker 可用且核心中间件运行数为 0 的互斥窗口完成真实双镜像构建、
Nginx Header/404、同源 API、HEALTHCHECK 和两标签回退验证。该结论只覆盖静态交付，
不替代后端真实 MySQL、Redis、Nacos、RocketMQ、MinIO 等三层证据。
