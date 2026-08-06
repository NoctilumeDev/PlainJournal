# Local Middleware Environment

This directory contains development-only middleware configuration. Business
data is not stored in the repository. The example environment writes
bind-mounted data under the ignored `deploy/docker/.data/` directory; a local
`.env` may override `MIDDLEWARE_DATA_ROOT` with another absolute or relative
location.

## Core services

| Service | Container | Host endpoint |
| --- | --- | --- |
| MySQL 8.4 | `plainjournal-mysql` | `127.0.0.1:13306` |
| Redis 7.4 | `plainjournal-redis` | `127.0.0.1:16379` |
| Nacos client | `plainjournal-nacos` | `127.0.0.1:8848` |
| Nacos console | `plainjournal-nacos` | `http://127.0.0.1:18080/` |
| RocketMQ NameServer | `plainjournal-rocketmq-namesrv` | `127.0.0.1:9876` |
| RocketMQ Broker | `plainjournal-rocketmq-broker` | `127.0.0.1:10911` |
| RocketMQ Remoting proxy | `plainjournal-rocketmq-proxy` | `127.0.0.1:18081` |
| RocketMQ gRPC proxy | `plainjournal-rocketmq-proxy` | `127.0.0.1:18082` |
| MinIO API | `plainjournal-minio` | `http://127.0.0.1:19000` |
| MinIO console | `plainjournal-minio` | `http://127.0.0.1:19001` |

Optional `observability` profile:

| Service | Container | Host endpoint |
| --- | --- | --- |
| Prometheus | `plainjournal-prometheus` | `http://127.0.0.1:19090` |
| Alertmanager | `plainjournal-alertmanager` | `http://127.0.0.1:19093` |
| Grafana | `plainjournal-grafana` | `http://127.0.0.1:13000` |
| Tempo query API | `plainjournal-tempo` | `http://127.0.0.1:13200` |
| Tempo OTLP/HTTP | `plainjournal-tempo` | `http://127.0.0.1:14318` |

On-demand M3 application profiles:

| Profile | Service | Purpose |
| --- | --- | --- |
| `m3-trade` | Trade | 1/2/3 instance, Outbox, consumer, and graceful-stop experiments |
| `m3-gateway` | Gateway | Gateway/Nacos release-governance experiments |

These profiles are not part of the normal middleware baseline. The verification
scripts start only the required application containers and remove them at the
end. `verify-gateway-rolling-upgrade.ps1` also creates one exact-name temporary
bridge network for release identities and removes it after the run.

On-demand M7 data profiles:

| Profile | Service | Host endpoint | Purpose |
| --- | --- | --- | --- |
| `m7-catalog-replica` | MySQL Catalog replica | `127.0.0.1:13316` | Catalog public-read routing, lag, recovery, and primary fallback |
| `m7-trade-sharding` | MySQL Trade shard 1 | `127.0.0.1:13326` | Trade two-shard lifecycle, archive migration, and controlled 2→4 resharding |

The M7 replica and Trade shard profiles are mutually exclusive with each other,
observability, and scale generation. The replica uses an independent bind-mounted data directory under
`MIDDLEWARE_DATA_ROOT/mysql-replica`, is never part of the seven-container core
baseline, and is removed as a container after the default verification run.
The verifier resets only the experimental replica schema and replication
metadata; it does not delete the bind-mounted directory or primary MySQL data.

Run the complete replica experiment from the backend directory:

```powershell
cd ../../backend
./tools/verify-m7-catalog-read-replica.ps1
```

The script performs the network preflight, creates a consistent Catalog snapshot,
pauses and resumes replication, stops and restarts the replica, verifies primary
fallback metrics, and removes the experimental container afterward.

The Trade shard profile adds one 512 MiB MySQL container. `ds_0` remains in the
core MySQL as `ecom_trade_shard_0`; `ds_1` is
`plainjournal-mysql-trade-shard-1/ecom_trade_shard_1`. Run the complete experiment from
the repository root:

```powershell
cd ..\..
.\backend\tools\verify-m7-trade-sharding.ps1
```

The two-shard verifier creates both schemas and least-scope users, runs Flyway V1–V14 on
each shard, starts at most five phased business JVMs, and verifies the full
payment/fulfillment/return/refund lifecycle. Each run uses isolated RocketMQ
topics and consumer groups and removes their Retry/DLQ artifacts afterward.
It also removes probe data, shard schemas/users, ports, JVMs, and the experiment
container.

The historical archive verifier reuses the same second-shard profile but does not
start business JVMs. It creates random source/archive schemas on both real MySQL
containers, applies Trade V1-V14, injects a committed-batch interruption, verifies
checkpoint resume, refreshes the candidate watermark, compares all 11 archived
tables, rejects a deliberate data mutation, promotes a read gate, rolls back only
archive copies, and replays the job:

```powershell
cd ..\..\backend
.\tools\verify-m7-trade-archive-migration.ps1
```

The verifier drops only its random schemas and restores
`plainjournal-mysql-trade-shard-1` to its pre-run state. This proves historical archive
migration and rollback by that tool.

Controlled active resharding reuses the same second MySQL container and creates
two source plus four target schemas across the two physical MySQL instances:

```powershell
cd ..\..
.\backend\tools\verify-m7-trade-resharding.ps1
```

It verifies checkpoint recovery, online source mutations, a final maintenance
write fence, full-column fingerprints, corruption blocking, four-shard routing,
restricted rollback, replay, and cleanup. It is not a zero-downtime CDC platform;
there is no reverse replication after target writes.

On-demand M8 attachment security profile:

| Profile | Service | Host endpoint | Purpose |
| --- | --- | --- | --- |
| `m8-malware-scan` | ClamAV 1.5.3 | `127.0.0.1:13310` | Chat quarantine object scanning, EICAR verification, scanner fault and audited recovery |

ClamAV has a 3 GiB memory limit and is not part of the seven-container core
baseline. Its signature database is bind-mounted under
`MIDDLEWARE_DATA_ROOT/clamav/database`. Run the complete M8.7 verification from
the backend directory:

```powershell
cd ..\..\backend
.\tools\verify-m8-chat-attachments.ps1
```

The verifier records the initial ClamAV container state, creates it only when
needed, performs one controlled stop/recovery cycle, and restores the initial
state. If the container did not exist before the run, it is removed afterward;
the signature database directory is retained. The verifier also removes its
Chat rows, scan audit rows, quarantine objects, application ports, and managed
JVMs.

On-demand M8 product-search profile:

| Profile | Service | Host endpoint | Purpose |
| --- | --- | --- | --- |
| `m8-search` | OpenSearch 3.7.0 | `127.0.0.1:19200` | Catalog disposable product-search projection, rebuild, fault recovery, and reconciliation |

OpenSearch uses a 512 MiB heap and a 1408 MiB container limit. It is not part of
the seven-container core baseline and must not be left running with unrelated
heavy profiles. The disposable local profile binds only to `127.0.0.1` and sets
`DISABLE_SECURITY_PLUGIN=true`; this is a single-machine verification setting,
not a production authentication or TLS baseline. Run the complete M8.11
verification from the backend directory:

```powershell
cd ..\..\backend
.\tools\verify-m8-catalog-search.ps1 -EvidenceDate 20260724
```

The verifier creates an isolated Catalog schema and a run-scoped index alias,
proves incremental projection, stops OpenSearch for one controlled failure,
verifies explicit MySQL fallback and terminal Outbox governance, performs
audited recovery, executes a blue-green rebuild, injects missing/stale/orphan
documents, reconciles them, and verifies unpublish deletion. It restores a
pre-existing OpenSearch container to its original running state; a container
created by the script is removed. Run-scoped indices, schema grants, ports and
Catalog JVMs must be absent at completion while all seven core containers remain
running. The final metrics check uses the dedicated `X-Metrics-Token` scrape
identity rather than an administrator login token. The completed mechanism and
evidence are summarized in the
[`M0-M8 three-layer acceptance`](../../docs/evidence/m0-m8-three-layer-acceptance-20260728.md).

M8.12 operational Analytics uses the seven core middleware containers plus one
temporary Analytics JVM and Gateway; it does not add another persistent
container:

```powershell
cd ..\..\backend
.\tools\verify-m8-analytics.ps1 -EvidenceDate 20260724
```

The verifier creates an isolated Analytics schema/user, two run-scoped topics
and one consumer group. It proves duplicate-event convergence, controlled
RocketMQ Proxy stop/recovery, legacy product-revenue coverage, bounded
missing/stale/orphan reconciliation, idempotent audited rebuilds, role
authorization, and dedicated metrics-token scraping. Cleanup removes the
temporary schema, user, topics, consumer group, ports, and JVMs, restores the
Proxy, and verifies all seven core containers remain running. Evidence is
summarized in the
[`M0-M8 three-layer acceptance`](../../docs/evidence/m0-m8-three-layer-acceptance-20260728.md).

M8.8 reliable Notification delivery uses the seven core middleware containers
plus three temporary JVMs and a minimal local SMTP capture process; it does not
add another persistent container:

```powershell
cd ..\..\backend
.\tools\verify-m8-notification-delivery.ps1 -SkipBuild
```

The verifier creates run-scoped Payment and logistics topics and a dedicated
consumer group, publishes a real Payment Outbox event, forces two SMTP connection
failures, verifies `NEEDS_ATTENTION`, rejects customer recovery, performs one
idempotent audited administrator retry, captures the final email with its stable
`Message-ID`, and records a poison event without exposing its raw payload through
Actuator. It removes run data, isolated topics, ports, the SMTP capture process,
and temporary JVMs in `finally`.

Host ports are bound to loopback only. The existing native MySQL, Redis, and RabbitMQ services are not modified.
The Nacos administrator username is `nacos`; its local password is stored in the ignored `.env` file.
RocketMQ Broker and cluster-mode Proxy run as separate containers but share one
network namespace. This preserves loopback-only host bindings while allowing the
Proxy to return the actual host or Compose-network entry point used by each client.

## Commands

Run the following commands from this directory:

```powershell
docker compose --env-file .env --profile core config
docker compose --env-file .env --profile core pull
docker compose --env-file .env --profile core up -d
docker compose --env-file .env --profile core ps
./bootstrap-resources.ps1
```

Run the observability stack only while metrics, dashboards, alerts, or traces are being
tested:

```powershell
docker compose --env-file .env --profile observability pull
docker compose --env-file .env --profile observability up -d
./verify-observability.ps1 -KeepRunning
docker compose --env-file .env --profile observability stop
```

`verify-observability.ps1` requires Inventory, Trade, Payment, and Fulfillment
to be running on ports `18103` through `18106`. Without `-KeepRunning`, it
restores the prior container running state after verification.
The script resolves the Compose file, project directory, and environment file
from its own directory, so callers do not need to change their current working
directory first.

The official `grafana/grafana:13.1.0` and `grafana/tempo:2.10.5` images were
verified on the local network baseline on 2026-07-18. Configure a registry mirror
only when the official registry is actually unavailable; do not change Docker's
global mirror settings for a transient proxy failure.

Stop containers without deleting D-drive data:

```powershell
docker compose --env-file .env --profile core down
```

The local `.env` file contains development credentials and is ignored by Git. Copy `.env.example` when creating a new environment. The bootstrap script creates the isolated `ecom_identity`, `ecom_catalog`, `ecom_inventory`, `ecom_trade`, `ecom_payment`, `ecom_fulfillment`, `ecom_marketing`, and `ecom_chat` schemas with separate least-scope application users. Missing database passwords, the identity JWT secret, the internal service token, the mock payment callback secret, the metrics scrape token, and the Grafana administrator password are generated once and appended only to the ignored `.env` file. The scrape token is mirrored into ignored `.runtime-secrets` for Compose secret mounting; its value is never printed.

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
- `chat-service.yml`
- `notification-service.yml`
- `analytics-service.yml`

The source-controlled files are under `deploy/docker/nacos`. Running
`bootstrap-resources.ps1` publishes their current contents to local Nacos.

RocketMQ topics:

- `ecommerce-order-events`
- `ecommerce-flash-sale-events`
- `ecommerce-inventory-events`
- `ecommerce-payment-events`
- `ecommerce-refund-events`
- `ecommerce-chat-events`
- `ecommerce-chat-delivery-events`
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
| Customer-service chat | MySQL message/receipt/upload facts, WebSocket, Redis presence/routing and digest-keyed single-use browser tickets, RocketMQ cross-node messages, private MinIO attachments and orphan cleanup |
| Logistics tracking | MySQL checkpoints, Redis GEO current position, RocketMQ updates |
| Notification | MySQL-owned in-app facts, RocketMQ idempotent consumers, SMTP lease/retry/audit delivery |

MySQL remains the final source of truth for orders, stock, payments, refunds,
chat messages, receipts, and offline replay. Redis and RocketMQ improve throughput
and decoupling but must not become the only copy of final business state.

The public gateway rejects every path containing an `/internal/` segment.
Trusted local services call internal endpoints directly through Nacos and attach
the generated `X-Internal-Service` and `X-Internal-Token` headers. This shared
secret is suitable for the isolated development network. A production rollout
must combine service credentials with TLS/mTLS, network policy, rotation, and a
secret manager rather than treating the header alone as a complete trust model.
