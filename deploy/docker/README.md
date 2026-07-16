# Local Middleware Environment

This directory contains development-only middleware configuration. Business data is not stored in the repository. All bind-mounted data is written under `D:/Middleware/ecommerce-platform`.

## Core services

| Service | Container | Host endpoint |
| --- | --- | --- |
| MySQL 8.4 | `ecom-mysql` | `127.0.0.1:13306` |
| Redis 7.4 | `ecom-redis` | `127.0.0.1:16379` |
| Nacos client | `ecom-nacos` | `127.0.0.1:8848` |
| Nacos console | `ecom-nacos` | `http://127.0.0.1:18080/` |
| RocketMQ NameServer | `ecom-rocketmq-namesrv` | `127.0.0.1:9876` |
| RocketMQ Broker | `ecom-rocketmq-broker` | `127.0.0.1:10911` |
| RocketMQ Remoting proxy | `ecom-rocketmq-broker` | `127.0.0.1:18081` |
| RocketMQ gRPC proxy | `ecom-rocketmq-broker` | `127.0.0.1:18082` |
| MinIO API | `ecom-minio` | `http://127.0.0.1:19000` |
| MinIO console | `ecom-minio` | `http://127.0.0.1:19001` |

Host ports are bound to loopback only. The existing native MySQL, Redis, and RabbitMQ services are not modified.
The Nacos administrator username is `nacos`; its local password is stored in the ignored `.env` file.

## Commands

Run the following commands from this directory:

```powershell
docker compose --env-file .env --profile core config
docker compose --env-file .env --profile core pull
docker compose --env-file .env --profile core up -d
docker compose --env-file .env --profile core ps
./bootstrap-resources.ps1
```

Stop containers without deleting D-drive data:

```powershell
docker compose --env-file .env --profile core down
```

The local `.env` file contains development credentials and is ignored by Git. Copy `.env.example` when creating a new environment. The bootstrap script creates the isolated `ecom_identity`, `ecom_catalog`, `ecom_inventory`, `ecom_trade`, `ecom_payment`, `ecom_fulfillment`, and `ecom_marketing` schemas with separate least-scope application users. Missing database passwords, the identity JWT secret, the internal service token, and the mock payment callback secret are generated once and appended only to the ignored `.env` file.

`APP_ENV` is part of every application-owned Redis key. Local keys therefore use
the `ecommerce:local:*` prefix and cannot collide with test or production data.
Identifiers such as email addresses and client IPs are SHA-256 hashed before
they become Redis key segments.

## Pre-created resources

Nacos configuration (`ECOMMERCE` group):

- `ecommerce-gateway.yml`
- `identity-service.yml`
- `catalog-service.yml`
- `inventory-service.yml`
- `trade-service.yml`
- `payment-service.yml`
- `fulfillment-service.yml`
- `marketing-service.yml`

The source-controlled files are under `deploy/docker/nacos`. Running
`bootstrap-resources.ps1` publishes their current contents to local Nacos.

RocketMQ topics:

- `ecommerce-order-events`
- `ecommerce-inventory-events`
- `ecommerce-payment-events`
- `ecommerce-refund-events`
- `ecommerce-chat-events`
- `ecommerce-logistics-events`
- `ecommerce-notification-events`
- `ecommerce-promotion-events`

MinIO private buckets:

- `product-media`
- `user-avatars`
- `chat-attachments`
- `review-media`
- `logistics-proofs`
- `after-sale-evidence`

The backend must issue signed URLs for private objects. Do not expose a bucket anonymously.

## Module mapping

| Module | Primary middleware responsibility |
| --- | --- |
| Gateway and identity | Nacos service discovery, Redis sessions/rate limits |
| Catalog and product | Redis hot data, MinIO product media |
| Inventory and order | MySQL conditional stock updates, optional Redis admission, RocketMQ Outbox events |
| Payment and refund | MySQL state machine, RocketMQ idempotent result events |
| Customer-service chat | WebSocket, Redis presence/routing, RocketMQ cross-node messages, MinIO attachments |
| Logistics tracking | MySQL checkpoints, Redis GEO current position, RocketMQ updates |
| Notification | RocketMQ consumers for email, SMS, and in-app notifications |

MySQL remains the final source of truth for orders, stock, payments, and refunds. Redis and RocketMQ improve throughput and decoupling but must not become the only copy of final business state.

The public gateway rejects every path containing an `/internal/` segment.
Trusted local services call internal endpoints directly through Nacos and attach
the generated `X-Internal-Service` and `X-Internal-Token` headers. This shared
secret is suitable for the isolated development network. A production rollout
must combine service credentials with TLS/mTLS, network policy, rotation, and a
secret manager rather than treating the header alone as a complete trust model.
