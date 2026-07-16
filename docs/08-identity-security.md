# 身份认证与令牌安全

## 1. 当前闭环

`identity-service` 已形成第一条完整业务切片：

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

## 3. 角色基线

当前预置角色为：

- `CUSTOMER`
- `ADMIN`
- `OPERATOR`
- `CUSTOMER_SERVICE`
- `WAREHOUSE`
- `FINANCE`

Spring Security 将 JWT 的 `roles` 声明映射为 `ROLE_*` authority。新增管理接口时使用 `@PreAuthorize` 或统一授权服务约束权限，不能只依赖前端隐藏按钮。

## 4. 已验证场景

- H2 隔离测试：注册、重复邮箱、错误密码、受保护接口、刷新旋转、注销与哈希存储。
- 并发测试：两个请求同时刷新同一令牌，只允许一个成功。
- 地址测试：默认地址切换、删除接替、输入校验、跨用户修改拒绝和 20 条上限规则。
- 内部接口测试：只有携带本地服务令牌的 `trade-service` 能读取指定用户自有地址；其他调用者和地址越权均拒绝。
- 真实环境烟测：Gateway、Nacos、MySQL、Flyway、JWT 全链路，无 Mock。
- 烟测结束自动删除临时账号并停止应用进程，不污染开发数据。

执行命令：

```powershell
cd C:\Users\lenovo\Desktop\ecommerce-platform\backend
mvn clean verify
./run-foundation-smoke.ps1
```

## 5. 明确边界与下一步

- Access Token 是短期自包含令牌，注销不会立即使已签发令牌失效，最长保留 15 分钟。需要即时冻结时，再增加 Redis 账号版本或黑名单校验，并保留数据库降级策略。
- 当前 HS256 适合首个服务切片。业务服务增多后改用非对称密钥或标准授权服务器，使业务服务只持有公钥，不能签发令牌。
- Redis 登录失败计数、邮箱临时锁定和网关限流已经实现，具体策略见《Redis 流量防护与降级》。
- 员工账号不能开放注册，后续由有权限的管理员创建并审计角色变更。
- 下单不会持有 identity 数据库事务。trade 通过受保护内部 API 获取一次地址并固化快照，不跨 schema 查询或建立外键。
