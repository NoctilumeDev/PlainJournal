# 前端视觉 V7.3：演示夹具与生产静态交付

> 日期：2026-08-03  
> 状态：完成；真实 Nginx 容器运行留到 V7.4 发布候选互斥窗口  
> 范围：公开演示说明、固定夹具账号、生产 dist、History fallback、缓存、Gateway/WS 边界、镜像与回退  
> 硬边界：不启动 Docker Desktop 和七个核心中间件，不修改业务事实或机器级网络

## 1. 结论

V7.3 已关闭 V7.1 的两项 P1 交付缺口：

- 仓库现在有明确、可复现且不冒充真实环境的本地演示账号和建议路径；
- 顾客端与管理端现在有 Nginx 静态部署模板、双镜像 target、Compose 项目标识、
  History fallback、分级缓存、Gateway/API、WebSocket 和版本回退边界。

生产浏览器门禁不再只运行 Vite 开发服务器。本批先生成两端 `dist`，再由 Vite
preview 读取生产 bundle，通过真实 Chromium 验证深层路由刷新、同源 API 代理、
错误密码拒绝、顾客会话恢复和管理端守卫恢复。

Nginx 配置和 Compose 已完成失败关闭检查与 `docker compose config` 解析，但本批
没有启动 Docker Desktop。根据本机网络文档，Docker 启动会自动恢复七个核心
中间件；为了一个静态服务器破坏资源互斥不符合当前单机边界。因此不能把本批描述为
“真实 Nginx 容器运行通过”。该运行证据进入 V7.4 发布候选的独立 Docker 窗口。

## 2. 演示环境与真实环境隔离

新增 `frontend/demo/README.md`，固定说明：

| 身份 | 邮箱 | 密码 | 角色 |
| --- | --- | --- | --- |
| 顾客 | `reader@example.com` | `ReaderPass123` | CUSTOMER |
| 第二顾客 | `reader-two@example.com` | `ReaderPass123` | CUSTOMER |
| 管理员 | `admin@example.com` | `AdminPass123` | ADMIN |

这些值是公开夹具，不是生产凭据。演示数据保存在 Mock API 进程内，停止后自然重置；
它不能替代 MySQL、Redis、Nacos、RocketMQ、MinIO、Gateway 和所有者服务证据。

Mock 登录此前对未知邮箱和任意密码也会返回顾客 Token，这对于自动化夹具虽然方便，
但不适合公开演示。本批改为精确匹配表内邮箱和密码；错误密码或未知邮箱返回
`401 INVALID_CREDENTIALS`。生产浏览器专项实际提交错误密码并验证 401，随后使用
正确密码完成登录。

演示命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm demo:start
pnpm demo:status
pnpm demo:stop
```

保留端口：

```text
Mock API   18090
Storefront 18300
Admin      18301
```

启动前发现端口占用会失败关闭，不结束未知进程后抢占端口。

## 3. 静态服务器合同

Nginx 只做交付和转发，不参与业务裁决：

| 路径 | 合同 |
| --- | --- |
| `/api/` | 转发 Gateway；不进入 SPA fallback |
| `/ws/` | 转发 Gateway；保留 Upgrade / Connection |
| `/assets/` | 缺失返回 404；内容哈希资源缓存一年、immutable |
| `/images/` | 缺失返回 404；稳定文件名缓存一天、允许七天 stale |
| `/index.html` | no-store / no-cache |
| 其他路由 | `try_files $uri $uri/ /index.html` |

`/images/` 没有使用 immutable，因为商品图片和响应式变体是稳定文件名而非内容哈希。
缺失 JS、CSS、图片、API 和 WebSocket 都不能返回 200 HTML。

模板同时添加：

- `X-Content-Type-Options: nosniff`；
- `X-Frame-Options: DENY`；
- `Referrer-Policy: strict-origin-when-cross-origin`；
- 禁用 camera、microphone 和 geolocation 的 `Permissions-Policy`。

## 4. 镜像、Compose 与回退

单一多阶段 Dockerfile 提供两个最终 target：

```text
storefront
admin
```

构建阶段使用 Node 24 与 pnpm 11，运行阶段使用 Nginx 1.28 Alpine。Compose 项目名
固定为 `plainjournal-frontend`，只发布到回环地址：

```text
127.0.0.1:18300 -> Storefront 8080
127.0.0.1:18301 -> Admin 8080
```

镜像名称由两个变量决定：

```text
PLAINJOURNAL_IMAGE_PREFIX
PLAINJOURNAL_FRONTEND_TAG
```

发布使用不可变版本标签。回退只切换到已经存在的旧标签并执行
`docker compose up -d --no-build`，不能在故障现场重新构建后声称已经回退。
前端回退不回滚数据库、消息版本或业务状态。

## 5. 生产浏览器证据

新增 3 条单 worker Chromium 专项：

1. 直接打开商品详情深层 URL，HTTP 返回生产 `index.html`，页面加载带哈希
   `/assets/*.js`、商品事实和 AVIF；刷新后仍停留在同一商品；
2. 所有 `/api/` 响应保持 `18300` 同源，不让浏览器直连 Mock API；
3. 错误顾客密码返回 401，正确登录后直接打开并刷新已完成订单；
4. 直接打开管理端 `/governance`，守卫保留 `/governance` return target；
5. ADMIN 登录后恢复治理页，刷新后仍显示“补偿与对账”；
6. 两端页面 warning、非预期 error 为 0。

Vite preview 只承担“生产 dist + History + 同源代理”的浏览器证据。它会对缺失
`/assets/*.js` 回退 HTML，因此不能用来证明 Nginx 的静态 404。该结论由具体
`assets/images` location 块解析测试负责，不能混用验证表面。

## 6. 首轮失败与修正

本批保留三次真实失败：

### 6.1 Preview 端口参数被吞

首轮启动把 `--` 当作普通参数传给 Vite，Storefront 实际监听默认 4173，而不是
18300。脚本等待超时后发现并核对残留进程命令行，只停止该 PlainJournal preview；
随后把命令固定为 `pnpm ... exec vite preview --host ... --port ...`。

### 6.2 Vite preview 不等于 Nginx

生产 E2E 最初要求缺失 `/assets/plainjournal-missing.js` 返回 404，但 Vite preview
返回 200 SPA HTML。没有放宽 Nginx 合同，而是把验证拆开：

- 浏览器继续证明生产 dist、深层路由与同源代理；
- Nginx 解析器分别提取 `/assets/`、`/images/`、`/api/`、`/ws/` 和 fallback 块，
  防止借用兄弟 location 的规则制造假绿灯。

### 6.3 URL 编码表现

管理端 return target 的实际 URL 是 `redirect=/governance`，测试硬编码为
`%2Fgovernance`。修正后按解析后的 origin、pathname 和查询值断言，不把等价编码
形式当成业务事实。

## 7. 自动化门禁

新增：

| 门禁 | 结果 |
| --- | ---: |
| 生产部署静态测试 | 3 / 3 |
| Docker Compose 解析 | 通过 |
| 生产 Chromium 专项 | 3 / 3 |
| 深层路由刷新 | 顾客端 / 管理端均通过 |
| 错误密码 | 401 |
| API 浏览器来源 | 18300 / 18301 同源 |
| fixture 端口 | 18090 / 18300 / 18301 全部释放 |

最终全量结果以本批结束时的 `pnpm check` 为准。标准 60 条开发态 E2E 与 3 条生产
E2E 分开执行，避免把只适用于生产 bundle 的哈希资源断言混进开发服务器门禁。

## 8. 当前已知边界

本批已经具备：

- Nginx 配置代码；
- 精确 location 自动化；
- Compose 解析；
- 生产 dist 浏览器证据；
- 演示账号与错误登录证据；
- 版本发布和回退说明。

本批尚未声称：

- Nginx 镜像已在 Docker 中构建；
- 实际 Nginx 响应 Header、缺失资源 404 和 HEALTHCHECK 已运行验证；
- 公网 Registry 推送或公网演示已经完成。

以上三项必须在 V7.4 发布候选的独立 Docker 窗口执行，且不得与七中间件全栈、
全量浏览器或压测并行。

## 9. 下一批

V7.4 处理 GitHub v1.0 展示和发布候选：

1. 顾客端与管理端最终截图和快速演示路径；
2. LICENSE、SECURITY、CHANGELOG 与 Release checklist；
3. Docker 可用窗口中的两镜像构建、Nginx Header/404、健康检查和标签回退演练；
4. README 成品叙事与发布版本冻结。

