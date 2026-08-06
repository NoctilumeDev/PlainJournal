# 身份认证与令牌安全

## 1. 当前闭环

`identity-service` 持有账号、地址、令牌、角色和登录风控事实：

```text
注册 -> BCrypt 密码落库 -> 默认 CUSTOMER 角色
登录 -> 密码校验与登录审计 -> access token + refresh token
访问 -> Spring Security 校验 JWT -> 读取当前账号与角色
刷新 -> 原 refresh token 条件撤销 -> 生成一对新令牌
注销 -> refresh token 撤销 -> 后续刷新被拒绝
```

服务使用独立 `ecom_identity` schema。Flyway 管理表结构和基础角色，应用账号只能访问该 schema，不使用 MySQL root 账号。

地址属于 identity：首个地址自动成为默认地址，单用户最多 20 条。创建、切换默认和删除默认地址时先锁定账号行，保证并发请求后仍最多只有一个默认地址。所有浏览器接口从 JWT subject 取用户 ID，不接受客户端代传用户 ID。

## 2. 令牌策略

| 项目 | 当前规则 |
| --- | --- |
| Access Token | HS256 JWT，15 分钟，仅包含用户 ID、角色、签发方与时间声明 |
| Refresh Token | 256 位随机不透明字符串，7 天 |
| 数据库存储 | 只保存 refresh token 的 SHA-256 哈希，不保存原文 |
| 刷新 | 每次成功后旋转；数据库条件更新保证并发请求仅一个成功 |
| 注销 | 撤销 refresh token；接口保持幂等，不暴露令牌是否存在 |
| 密码 | BCrypt strength 12；注册密码限制 72 UTF-8 字节以内 |

JWT 密钥只存在本地忽略的 `.env` 或部署环境的密钥系统中，不发布到 Nacos，也不写入源码。日志禁止输出密码、access token 和 refresh token。

## 3. 浏览器会话边界

M4 第二、第三批采用与现有 Identity API 相容的设备会话策略：

- access token 只保存在前端内存，不写入 `localStorage`；
- refresh token 保存在当前设备，用于刷新页面后的会话恢复；
- 恢复时先调用 `/auth/refresh` 完成令牌轮换，再用新 access token 调用 `/me`；
- 并发恢复复用同一个 Promise，避免同一 refresh token 被并发旋转；
- 服务端注销确认成功后才清除本机会话；
- 注销网络失败或超时时保留会话并明确显示“结果未知”，用户可稍后重试或显式选择“仅清除此设备”。

refresh token 仍由 JavaScript 可读，这是当前“响应体返回 refresh token”契约下的明确边界。后续若切换为 `HttpOnly + Secure + SameSite` Cookie，需要同时补齐 CSRF、跨域、刷新轮换和多端设备管理，不能只改前端存储位置。

`UserProfile.id` 和 `AddressView.id` 已按 DTO 局部序列化为 JSON string，避免 Snowflake ID 在浏览器中静默丢失精度。没有启用全局 `Long -> string`。

顾客端 `/account/addresses` 已接通完整地址管理：新增或修改只有在 Identity 返回成功后才显示确认；切换默认地址以后端返回事实重载列表；删除使用原位确认，失败或结果未知时不关闭确认并不宣称删除成功。进入编辑状态后焦点移动到表单标题，状态与错误分别使用语义化 `status`/`alert`。

## 3.1 CSRF 边界

Gateway 与 10 个业务服务均是无状态 OAuth2 Resource Server：浏览器只在
`Authorization: Bearer` 请求头中显式携带 access token，服务端不创建 HTTP
session，Gateway 也不保存 SecurityContext。因此当前接口不接受浏览器自动附带的
Cookie 凭据，CSRF 防护不适用于这条 Bearer 协议，Spring Security 配置显式关闭
CSRF。

这个结论不是约定俗成的注释。仓库的
`tools/stateless-bearer-security.test.mjs` 会校验所有关闭 CSRF 的安全配置同时：

- 使用 OAuth2 Resource Server JWT 校验；
- 明确声明 `STATELESS` session，或在 Gateway 使用 NoOp SecurityContext；
- 没有启用表单登录、HTTP Basic 或 remember-me 等浏览器凭据流。

未来一旦把 refresh token 或认证状态迁移到 `HttpOnly` Cookie，必须在同一个版本内
重新启用匹配的 CSRF 防护，并同时审查 SameSite、CORS、跨域刷新和登出接口；不能沿用
当前的关闭配置。

## 4. 角色基线

当前预置角色为：

- `CUSTOMER`
- `ADMIN`
- `OPERATOR`
- `CUSTOMER_SERVICE`
- `WAREHOUSE`
- `FINANCE`

Spring Security 将 JWT 的 `roles` 声明映射为 `ROLE_*` authority。新增管理接口时使用 `@PreAuthorize` 或统一授权服务约束权限，不能只依赖前端隐藏按钮。

管理端当前只接受 `ADMIN` 或 `OPERATOR` 进入工作区。普通 `CUSTOMER` 即使账号密码验证成功，也会进入明确的权限不足页面；前端会清除本地管理会话，并尝试撤销刚签发的 refresh token。员工账号仍不开放自助注册。

## 5. 已验证场景

- H2 隔离测试：注册、重复邮箱、错误密码、受保护接口、刷新旋转、注销与哈希存储。
- 并发测试：两个请求同时刷新同一令牌，只允许一个成功。
- 地址测试：默认地址切换、删除接替、输入校验、跨用户修改拒绝和 20 条上限规则。
- 内部接口测试：只有携带本地服务令牌的 `trade-service` 能读取指定用户自有地址；其他调用者和地址越权均拒绝。
- 真实环境烟测：Gateway、Nacos、MySQL、Flyway、JWT 全链路，无 Mock。
- M4 第二批真实最小链路：注册、登录、刷新轮换、`/me`、地址创建和注销均通过 Gateway；用户与地址 ID 为 JSON string；注销后原 refresh token 返回 401。
- M4 第三批真实最小链路：地址列表、新增、编辑、默认地址切换、删除取消和最终删除均通过 Gateway；页面刷新后的会话恢复正常，所有地址 ID 保持 JSON string。
- 烟测结束自动删除临时账号并停止应用进程，不污染开发数据。

执行命令：

```powershell
cd backend
./mvnw.cmd clean verify
./run-foundation-smoke.ps1
```

## 6. 当前安全边界

- Access Token 是短期自包含令牌，注销不会立即使已签发令牌失效，最长保留 15 分钟。需要即时冻结时，再增加 Redis 账号版本或黑名单校验，并保留数据库降级策略。
- 当前 HS256 是本地参考环境的明确边界。公网或多团队部署应改用非对称密钥或标准
  授权服务器，使业务服务只持有验证公钥，不能签发令牌。
- Redis 登录失败计数、邮箱临时锁定和网关限流已经实现，具体策略见《Redis 流量防护与降级》。
- 员工账号禁止公开自助注册。当前演示账号通过受控迁移和环境引导创建；管理员自助
  开户不是当前仓库能力，真实部署必须使用独立的受控开通和角色审计流程。
- 下单不会持有 identity 数据库事务。trade 通过受保护内部 API 获取一次地址并固化快照，不跨 schema 查询或建立外键。
