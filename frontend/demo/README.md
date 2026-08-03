# 素简记本地演示夹具

本目录说明的是可公开复现的本地演示夹具，不是真实生产账号，也不替代 MySQL、
Redis、Nacos、RocketMQ、MinIO、Gateway 或所有者服务的真实链路证据。

演示夹具使用生产构建产物、Vite preview 和受控 HTTP 数据。它只用于展示页面、
路由、角色导航、结果未知状态和响应式图片，不应用于资金、库存、权益或权限正确性
验收。

## 启动

```powershell
cd PlainJournal\frontend
pnpm demo:start
```

入口：

| 应用 | 地址 |
| --- | --- |
| 顾客端 | `http://127.0.0.1:18300` |
| 管理端 | `http://127.0.0.1:18301` |

状态与停止：

```powershell
pnpm demo:status
pnpm demo:stop
```

`18090/18300/18301` 是演示夹具保留端口。启动脚本发现端口已被占用时会失败关闭，
不会结束未知进程后强行接管。

## 固定账号

| 身份 | 邮箱 | 密码 | 权限 |
| --- | --- | --- | --- |
| 顾客 | `reader@example.com` | `ReaderPass123` | CUSTOMER |
| 第二顾客 | `reader-two@example.com` | `ReaderPass123` | CUSTOMER；用于所有者隔离测试 |
| 管理员 | `admin@example.com` | `AdminPass123` | ADMIN |

Mock API 只接受表内邮箱与对应密码。未知邮箱或错误密码返回 401，不能因为是演示环境
而伪造登录成功。

这些密码是仓库公开夹具数据，禁止复制到任何公网部署、真实数据库或个人账号。若以后
发布在线演示，必须使用独立临时环境、限权账号、可重置数据和速率限制。

## 建议演示路径

顾客端：

```text
首页
→ 商品详情
→ 登录
→ 购物袋 / 结算
→ 订单详情
→ 履约、评价与整单售后
→ 通知和客服会话
```

管理端：

```text
登录
→ 运营首页
→ Catalog 公开投影
→ Fulfillment / Inventory / Marketing
→ 售后、评价与 Chat
→ Payment 补偿和四域只读对账
```

夹具在进程内保存写入结果，停止后自然重置，不应被描述为持久化演示环境。
