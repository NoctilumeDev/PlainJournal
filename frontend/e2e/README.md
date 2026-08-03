# 浏览器验收夹具

`mock-api.mjs` 只用于顾客端和管理端的浏览器验收，不参与生产构建，也不替代真实
MySQL、Nacos、Gateway、Identity、Trade 与 Marketing 链路。

启动顺序：

```powershell
pnpm dev:mock-api
pnpm dev:storefront
pnpm dev:admin
```

当前夹具覆盖：

- 顾客登录；
- 地址列表、新增、修改、默认地址与删除；
- Trade 账户购物车读取；
- Marketing 无副作用试算；
- CUSTOMER 登录管理端后的明确权限不足。
- 顾客权益中心、售后详情、退货寄回与退款处理中事实；
- ADMIN 登录、履约拣货和四域治理工作区。

夹具固定使用超过 JavaScript 安全整数范围的业务 ID，以便在真实浏览器中持续验证
`BusinessId = string` 边界。

自动门禁使用 Playwright 驱动本机 Chrome，并通过 axe-core 检查关键页面的
serious / critical 可访问性违规：

```powershell
pnpm e2e
```

该门禁验证浏览器交互、路由恢复、角色导航、主题持久化和错误控制台；Mock API
不替代真实 MySQL、Redis、Nacos、RocketMQ、Gateway 与故障代理脚本。
