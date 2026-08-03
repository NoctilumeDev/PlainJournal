# 前端视觉 V7.2：响应式图片交付

> 日期：2026-08-03  
> 状态：完成  
> 范围：两张商品图、一张履约插图、共享响应式图片 primitive、浏览器网络取证  
> 硬边界：不修改 API、业务状态、权限、幂等恢复、所有者事实或机器级环境

## 1. 结论

V7.2 已把 V7.1 识别出的 5.59 MiB 原始 PNG 交付缺口关闭。三张 PNG 继续保留为
可追溯源和不支持现代格式时的 fallback；浏览器优先使用 AVIF，其次 WebP，并按实际
展示宽度选择响应式尺寸。

本批没有重绘商品、改变商品事实或用生成图替换原图。转换由仓库脚本确定性完成，
管理端复用顾客端公开商品资源目录，不复制第二份商品图片资产。

## 2. 交付矩阵

| 图片 | 原始尺寸 | 响应式宽度 | 现代格式 |
| --- | ---: | --- | --- |
| 帆布通勤袋 | 1122 × 1402 | 480 / 800 / 1122 | AVIF + WebP |
| 雾蓝笔记本 | 1122 × 1402 | 480 / 800 / 1122 | AVIF + WebP |
| 青荷履约路线 | 1672 × 941 | 640 / 1024 / 1672 | AVIF + WebP |

编码参数：

```text
AVIF quality 64
WebP quality 84
```

体积结果：

| 资产 | 文件数 | 总体积 |
| --- | ---: | ---: |
| 原始 PNG | 3 | 5.59 MiB |
| 响应式 AVIF/WebP | 18 | 860.6 KiB |
| 现代格式候选相对原图 | - | 下降 85.0% |

这里的 85.0% 是全部现代变体集合与三张原图集合的保守比较，不是假设单次页面会下载
全部 18 张。真实浏览器只会从 `picture/srcset/sizes` 中选择当前视口需要的来源。

## 3. 代码边界

新增共享无业务 primitive：

- `packages/ui/src/PjResponsiveImage.vue` 只负责 `picture/source/img` 语义；
- `packages/ui/src/responsiveImage.ts` 保存当前随仓库交付的商品图片尺寸合同；
- 页面继续传入业务 `alt`、`sizes`、加载优先级和 fallback，不在 primitive 中识别
  商品、订单或履约状态。

已迁移消费者：

- 首页主商品图；
- 商品卡片与目录；
- 商品详情主图和缩略图；
- 游客购物袋；
- 管理端 Catalog 公开投影；
- 订单详情履约插图。

管理端 Vite 的 `publicDir` 指向顾客端公开资源目录，因此两个应用使用同一份商品图片
源文件。履约插图属于顾客端构建资产，继续由 Vite 生成带内容哈希的生产文件。

## 4. 加载策略

来源顺序固定为：

```text
AVIF
→ WebP
→ PNG fallback
```

首页主视觉和商品详情主图使用 eager 加载与高优先级；商品卡、缩略图、购物袋和履约
插图保持延迟加载。`width/height` 或固定比例容器在请求完成前预留布局，避免图片解码
造成无意义跳动。

商品接口仍返回稳定 PNG URL。`resolveCatalogImageDelivery` 只对仓库内已登记、已生成
且尺寸已知的图片扩展现代来源；未知 CDN、MinIO 或外部 URL 保持原样，不猜测不存在
的变体。

## 5. 可复现生成与失败关闭

生成与检查命令：

```powershell
cd C:\Users\lenovo\Desktop\PlainJournal\frontend
pnpm images:generate
pnpm images:check
```

`tools/image-variants.py` 固定源文件、尺寸和质量参数。`--check` 会逐项验证 18 个目标
文件存在、可解码且像素尺寸正确。

`pnpm check:delivery` 已从“大 PNG 仅警告”升级为失败关闭：

- 每个正式 PNG 必须同时存在 AVIF 与 WebP 变体；
- 每个响应式变体不得超过 256 KiB；
- 当前资产合同固定为 3 张原图和 18 张现代变体；
- 交付门禁继续检查路由、公开临时标识和退役选择器。

## 6. 浏览器网络证据

新增两条真实 Chromium 用例：

1. 顾客端依次打开首页、商品详情和已完成订单，读取 `HTMLImageElement.currentSrc`；
2. 管理端登录后打开 Catalog，读取共享商品图的 `currentSrc`；
3. 截获真实图片响应，断言状态 200、`Content-Type: image/avif`；
4. 断言商品和履约 PNG fallback 没有作为 `image` 资源请求；
5. 同时保持控制台错误和页面异常为零。

首轮完整 E2E 为 58 条通过、V7 第一条失败、第二条因串行组停止未运行。失败不是产品
下载了 PNG，而是测试把 Vite 开发服务器的
`/qinghe-parcel-route.png?import` JavaScript 模块元数据误算成图片；trace 证明该请求
的资源类型是 `script`、响应类型是 `text/javascript`，真正的像素请求为 AVIF。

取证器随后改为只记录 `request.resourceType() === "image"` 的响应，没有删除 PNG
禁止断言，也没有放宽 AVIF 类型断言。完整 60 条用例重跑全部通过。

## 7. 最终门禁

| 门禁 | 结果 |
| --- | ---: |
| 图片生成合同 | 18 / 18 |
| V7 交付静态测试 | 3 / 3 |
| 分层规则 | 28 / 28 |
| 分层文件 / 相对导入 | 147 / 262 |
| 前端单元/契约 | 303 / 303 |
| 类型检查 | 通过 |
| 两端生产构建 | 通过 |
| Playwright 真实 Chromium | 60 / 60 |
| V7.2 图片网络专项 | 2 / 2 |
| PNG fallback 图片请求 | 0 |
| 项目监听 | 18000 / 18090 / 18200 / 18201 全部释放 |

本批受控 Mock 只提供稳定页面事实。图片选择、HTTP 响应类型和 fallback 请求是浏览器
真实网络证据；资金、库存、权益、权限和跨服务一致性没有被本批修改，继续引用
M0–M8 已封存的三层证据。

## 8. 下一批

V7.3 处理演示与静态部署：

1. 建立不泄露真实凭据的演示数据和角色账号说明；
2. 落地生产 History fallback、静态资源缓存和 Gateway/API 转发边界；
3. 验证刷新深层路由、资源缓存、错误页和部署回退；
4. 继续串行运行，不与 Docker、真实中间件或浏览器录制并行争抢资源。

README 截图、LICENSE/SECURITY/CHANGELOG 和最终 GitHub v1.0 发布清单留到后续独立
切片，不与部署批次混在一起。
