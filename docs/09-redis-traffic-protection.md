# Redis 流量防护与降级

## 1. 两层职责

```text
客户端
  -> Gateway：按来源 IP + 接口限流
  -> Identity：按规范化邮箱累计密码失败次数
  -> MySQL：账号、密码哈希、登录审计与令牌最终事实
```

网关处理单个来源对公共接口的高频请求，身份服务处理攻击者更换 IP 后仍持续尝试同一邮箱的情况。两层不能相互替代。

## 2. 当前策略

| 位置 | 规则 | 超限响应 |
| --- | --- | --- |
| 登录接口 | 每 IP 每分钟 10 次 | `429 GATEWAY_RATE_LIMITED` |
| 注册接口 | 每 IP 每分钟 5 次 | `429 GATEWAY_RATE_LIMITED` |
| 刷新接口 | 每 IP 每分钟 30 次 | `429 GATEWAY_RATE_LIMITED` |
| 秒杀准入接口 | 默认每 IP 每秒 60 次，活动验证可独立覆盖 | `429 GATEWAY_RATE_LIMITED` |
| 邮箱密码失败 | 15 分钟内 5 次，锁定 30 分钟 | `429 LOGIN_TEMPORARILY_LOCKED` |

网关响应包含 `Retry-After` 和 `X-RateLimit-Policy`。邮箱锁定对存在和不存在的账号使用相同行为，避免通过响应差异枚举用户。

## 3. Redis 与本地降级

- Redis 正常时，Lua 脚本原子完成 `INCR` 和 TTL 设置，多实例共享计数；此时只以 Redis 结果为准。
- 每个实例保留有容量上限的 Caffeine 本地窗口，但只在 Redis 禁用、超时或断开时启用，避免两套独立计数在并发下因到达顺序不同而错误损失名额。
- Redis 恢复后重新使用全局计数，并清除对应本地窗口。
- 本地缓存最多 100000 个标识并自动过期，防止随机邮箱或 IP 导致无界内存增长。
- 降级和恢复只在状态切换时记录日志，不重复刷屏。

本地降级只能保证单实例限制。Redis 故障期间，多网关或多身份实例之间无法共享计数，这是可用性优先的明确取舍；MySQL 中的账号和登录审计不受影响。

## 4. Key 规范

```text
ecommerce:{APP_ENV}:gateway:rate:{policy}:{sha256(ip)}
ecommerce:{APP_ENV}:identity:login:failures:{sha256(email)}
ecommerce:{APP_ENV}:identity:login:lock:{sha256(email)}
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:meta
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:user:{userId}
ecommerce:{APP_ENV}:marketing:flash-sale:activity:{activityNo}:request:{sha256(userId:idempotencyKey)}
ecommerce:{APP_ENV}:marketing:flash-sale:token:{requestToken}
```

Key 包含环境命名空间，且不写入邮箱和 IP 明文。所有计数与锁都有 TTL，Redis 不保存账号或权限最终状态。

## 5. 网络边界

当前网关直接使用 TCP 远端地址，不信任客户端自行传入的 `X-Forwarded-For`。部署到受控反向代理之后，必须先限定可信代理地址，再解析转发头；否则攻击者可以伪造 IP 绕过限流。

## 6. 验证

```powershell
cd backend
./mvnw.cmd clean verify
./run-foundation-smoke.ps1
```

自动测试覆盖本地窗口、接口匹配、稳定 `429` 响应、第五次邮箱失败锁定和重置恢复。真实烟测覆盖 Redis key、Lua 主路径、网关限流，并实际暂停 `plainjournal-redis` 验证登录降级，最后自动恢复容器、删除 key 与烟测数据。

M6 活动准入使用不同的失败策略：Gateway Redis 故障仍可使用有界本地限流，但 Marketing 的活动准入门闩必须返回 `503`，不能本地放行或把 Redis 数量当成最终库存。专项验证命令为：

```powershell
./tools/verify-m6-flash-sale-admission.ps1 -EnableRedisFaultInjection
```

最终运行结论见
[M0-M8 三层工程验收](evidence/m0-m8-three-layer-acceptance-20260728.md)。
