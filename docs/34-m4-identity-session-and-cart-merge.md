# M4 身份会话与游客购物袋合并

> 日期：2026-07-20  
> 状态：M4 第二批完成

## 1. 本批结论

本批在 M4 第一批公开 Catalog 和设备游客购物袋之上，完成：

- Identity 用户与地址浏览器 ID 字符串契约；
- 顾客注册、登录、刷新恢复、账户页和退出边界；
- 管理端员工登录与 `ADMIN/OPERATOR` 角色门禁；
- Trade 购物车 ID 字符串契约；
- 不覆盖账户原购物车、可安全重试的游客购物袋合并；
- 后端全量、PMD、前端检查和最小真实链路验证。

本批没有实现结算、订单或支付页面，也没有把 RocketMQ、MinIO 或观测栈伪装成本批验证范围。

## 2. 浏览器业务 ID 契约

本批按 DTO 局部增加 `ToStringSerializer`：

| 服务 | DTO 字段 |
| --- | --- |
| Identity | `UserProfile.id` |
| Identity | `AddressView.id` |
| Trade | `CartItemView.id/productId/skuId` |

请求中的十进制字符串仍由 Jackson 精确解析为 Java `Long`。没有启用全局 `Long -> string`，因此 Outbox 载荷、请求哈希、内部服务 DTO 和双版本事件语义不受影响。

HTTP 集成测试断言注册、`/me`、地址创建/列表和购物车响应中的业务 ID 均为 JSON string。

## 3. 顾客会话

### 3.1 页面与路由

| 路由 | 能力 |
| --- | --- |
| `/login` | 顾客登录、安全相对 `returnTo`、登录后购物袋合并 |
| `/register` | 注册并立即建立会话 |
| `/account` | 当前用户、角色、购物袋合并状态与退出 |

公开 Catalog 浏览不要求登录；`/account` 由路由门禁保护。

### 3.2 令牌策略

- access token 只保存在内存；
- refresh token 保存在当前设备；
- 页面恢复时先调用 `/auth/refresh`，保存轮换后的 refresh token，再调用 `/me`；
- 同一页面生命周期的并发恢复复用同一个 Promise；
- 401 表示 refresh token 无效或过期，此时清除本机会话；
- 网络或超时不被转换成“会话恢复成功”。

当前 refresh token 可被 JavaScript 读取，是既有响应体契约的明确安全边界。后续若迁移到 HttpOnly Cookie，必须连同 CSRF、SameSite、跨域和设备会话管理一起设计。

### 3.3 退出真实性

顾客和员工退出都遵守：

1. 先请求 Identity 撤销 refresh token；
2. 服务端确认成功后清除本机会话；
3. 网络失败或超时时保持当前会话并显示结果未知；
4. 用户可以稍后重试，或显式选择“仅清除此设备”。

“仅清除此设备”不会宣称服务端 refresh token 已撤销。

## 4. 管理端角色门禁

管理端登录继续复用 Identity 账号和 JWT，但工作区只接受：

- `ADMIN`
- `OPERATOR`

普通 `CUSTOMER` 即使密码正确，也不会进入管理壳：

- 前端显示明确权限不足；
- 展示 Identity 返回的角色事实；
- 尝试注销刚签发的 refresh token；
- 无论远端注销是否确认，都清除本地管理端令牌；
- 后端管理接口仍必须独立执行 Spring Security 授权，前端门禁不是安全边界。

员工账号不开放自助注册。角色分配继续由受审计的后台能力或本地验证数据准备完成。

## 5. 游客购物袋合并

### 5.1 API

```text
POST /api/v1/trade/cart/guest-merge
Idempotency-Key: guest-merge:<device-generated-key>
Authorization: Bearer <access-token>
```

请求：

```json
{
  "items": [
    {
      "productId": "2079000000000000001",
      "skuId": "2079000000000000011",
      "quantity": 2
    }
  ]
}
```

现有 `PUT /cart/items/{skuId}` 是“设置数量”，不能用于合并。新接口使用“账户已有数量 + 游客数量”，达到上限时按服务端数量约束裁剪，不覆盖已有数量。

### 5.2 MySQL 裁决

V11 新增：

- `cart_user_lock`
- `cart_merge_request`

处理流程：

1. 请求按 SKU 排序，拒绝重复 SKU；
2. 对规范化的 `productId/skuId/quantity` 计算 SHA-256；
3. 在事务内锁定用户购物车写入行；
4. 按 `(user_id, merge_key)` 声明合并请求；
5. 同键不同哈希返回 `IDEMPOTENCY_CONFLICT`；
6. 首次请求累加购物车，同键重试跳过累加；
7. 合并请求与购物车变更在同一 MySQL 本地事务提交。

普通购物车 PUT、删除和游客合并共用用户写锁，避免相互覆盖。MySQL 是最终裁决；Redis 不参与购物车正确性。

### 5.3 前端结果未知恢复

前端把待合并快照单独保存为：

- 稳定 merge key；
- 绑定的用户 ID；
- 固定商品 ID、SKU ID 和数量。

收到成功前：

- 本地商品不删除；
- 网络/超时保留原 key 和原载荷；
- 用户新增的同 SKU 数量不会被成功回执一起误删；
- 未确认请求不能自动转移到另一账户。

服务端成功后，前端只扣除本次已提交数量；请求期间新增的本地数量继续保留。

## 6. 自动化证据

### 6.1 后端

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\backend
mvn clean verify
mvn org.apache.maven.plugins:maven-pmd-plugin:3.28.0:check
```

结果：

- 11 个 Reactor 模块成功；
- 43 份 Surefire 报告；
- 148 个测试；
- 0 失败、0 错误、0 跳过；
- Trade 58 个测试；
- PMD Maven Plugin 3.28.0 / PMD 7.17.0，0 违规。

游客合并自动化覆盖：

- 账户已有数量累加；
- 同键顺序重试不重复；
- 同键不同载荷冲突；
- 20 路同键并发只累加一次；
- 购物车浏览器 ID 为字符串。

### 6.2 前端

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm check
```

使用 Node.js 24.14.0。结果：

- foundation 5 个测试；
- storefront 11 个测试；
- admin 1 个测试；
- 共 17 个测试；
- 两端类型检查通过；
- 两端生产构建通过。

前端自动化覆盖 refresh token 轮换恢复、注销结果未知、购物袋稳定重试键、跨账户保护和管理端角色判断。

## 7. 真实最小链路

启动范围：

- MySQL 8.4
- Redis 7.4
- Nacos 3.2
- Gateway
- Identity
- Catalog
- Trade

Trade 通过命令行最高优先级关闭 Outbox、RocketMQ 消费器、恢复和对账调度。RocketMQ、MinIO、Prometheus、Grafana、Alertmanager 和 Tempo 未启动。

真实验证：

| 场景 | 结果 |
| --- | --- |
| Gateway/Identity/Catalog/Trade 健康与 Nacos 注册 | 通过 |
| Trade V11 在真实 MySQL 校验 | 通过 |
| 注册、刷新轮换、`/me`、地址、注销 | 通过 |
| User/Address/Product/SKU/Cart ID 类型 | `String` |
| 账户数量 3 + 游客数量 2 | 5 |
| 同键同载荷重试 | 仍为 5 |
| 同键不同载荷 | `409 IDEMPOTENCY_CONFLICT` |
| 冲突后的购物车数量 | 仍为 5 |
| 注销后再次刷新 | 401 |

临时账号、角色、地址、商品、SKU、购物车、用户锁和合并请求均按精确 ID 清理，复查计数为 0。四个 Java 进程、三个容器和本次启动的 Docker Desktop 均已停止，数据卷保留。

## 8. 下一批

1. 顾客地址 CRUD 与默认地址页面。
2. 账户购物车读取与结算草稿。
3. Marketing 试算与优惠分摊展示。
4. 逐项修复结算涉及的 Trade/Marketing/Inventory 浏览器 ID。
5. 登录、地址、购物袋和权限不足的首批 E2E 与可访问性检查。

地址与营销试算稳定前，不进入订单提交和支付页面。

## 9. 2026-07-30 当前实现覆盖说明

本文件保留 M4 第二批历史交付事实。后续前端低耦合第五批已把设备游客袋、
Trade 账户购物车和会话合并编排正式分层，并补齐数量、selected、删除、跨 owner
迟到响应隔离、PUT/merge 响应丢失和真实浏览器/F12 证据；Trade 后端协议和本文件
记录的 MySQL 幂等机制没有改变。当前状态以
[前端低耦合分层第五批](75-frontend-shopping-bag-layering-fifth-slice-20260730.md)
为准。
