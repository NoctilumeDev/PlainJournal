# 商品目录服务

## 1. 实现边界

`catalog-service` 是第二条完整业务纵切，拥有独立的 `ecom_catalog` schema，负责：

- 分类与品牌；
- SPU 商品信息；
- SKU 规格、销售价与划线价；
- 草稿、上架、下架状态；
- 私有 MinIO 商品图片引用与临时访问地址。

库存数量、库存预占和扣减不属于目录服务。后续 `inventory-service` 以 SKU ID 为业务关联，但不能直接写目录表。

## 2. 状态和一致性

```text
DRAFT -> ACTIVE -> INACTIVE
           ^          |
           +----------+
```

- 新商品必须至少包含一个 SKU，并以 `DRAFT` 创建。
- 只有存在可用 SKU 的商品才能发布。
- 公开接口只返回 `ACTIVE` 商品，草稿和下架商品返回 `404`。
- SPU、SKU、分类和品牌包含 `version` 字段；管理修改采用 MyBatis-Plus 乐观锁。
- SPU 与初始 SKU 在同一个本地 MySQL 事务中写入。
- 金额使用 MySQL `DECIMAL(18,2)` 和 Java `BigDecimal`。

## 3. 权限边界

目录服务复用身份服务签发的 HS256 JWT，并验证相同的 issuer。角色来自 `roles` claim：

| 接口 | 权限 |
| --- | --- |
| 分类、品牌、商品列表与详情 GET | 公开 |
| `/api/v1/catalog/admin/**` | `ADMIN` 或 `OPERATOR` |

顾客持有合法 JWT 也不能写目录数据。目录服务独立执行授权，不依赖网关替它兜底。

## 4. 图片直传

```text
管理前端 -> catalog: 申请上传意图
catalog -> MinIO: 生成短期 PUT URL
管理前端 -> MinIO: 直接上传图片
管理前端 -> catalog: 确认对象键
catalog -> MinIO: stat 校验 MIME 与大小
catalog -> MySQL: 保存 product_media
```

允许 `JPEG`、`PNG`、`WebP`，单文件默认不超过 10 MB。Bucket 保持私有，数据库只保存对象键和元数据。公开读取时生成短期 GET URL；如果 MinIO 暂时不可用，商品文字、SKU 和价格仍返回，媒体 URL 为 `null`。

确认接口只接受当前商品前缀 `products/{spuId}/` 下的对象键，并校验可选 SKU 确实属于该 SPU，避免跨商品引用。

## 5. 主要接口

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/catalog/categories` | 活跃分类 |
| `GET` | `/api/v1/catalog/brands` | 活跃品牌 |
| `GET` | `/api/v1/catalog/products` | 已发布商品分页与筛选 |
| `GET` | `/api/v1/catalog/products/{id}` | 已发布商品详情 |
| `POST` | `/api/v1/catalog/admin/categories` | 创建分类 |
| `POST` | `/api/v1/catalog/admin/brands` | 创建品牌 |
| `POST` | `/api/v1/catalog/admin/products` | 创建 SPU 和初始 SKU |
| `PUT` | `/api/v1/catalog/admin/products/{id}` | 更新 SPU |
| `POST` | `/api/v1/catalog/admin/products/{id}/publish` | 上架 |
| `POST` | `/api/v1/catalog/admin/products/{id}/unpublish` | 下架 |
| `PUT` | `/api/v1/catalog/admin/products/{id}/skus/{skuId}` | 更新 SKU 与价格 |
| `POST` | `/api/v1/catalog/admin/products/{id}/media/upload-intents` | 申请 PUT URL |
| `POST` | `/api/v1/catalog/admin/products/{id}/media` | 确认媒体对象 |

## 6. 验证方式

```powershell
cd C:\Users\lenovo\Desktop\ecommerce-platform\backend
mvn clean verify
../deploy/docker/bootstrap-resources.ps1
./run-foundation-smoke.ps1
```

H2 集成测试覆盖权限、草稿隔离、发布、金额精度、乐观锁、媒体确认和 MinIO 读取降级。真实冒烟使用 MySQL、Nacos、Redis、MinIO 和网关，结束后自动删除临时账号、商品、对象与 Redis key，并停止三个应用进程。
