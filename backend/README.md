# Backend Foundation and Transaction Core

This directory contains the Spring Boot 3 multi-module backend. The current
foundation deliberately starts with one complete vertical slice so that service
discovery, routing, configuration, persistence, security, and test conventions
are proven before more business services are added.

## Modules

```text
backend/
├── platform-common/              # Technical API contracts only
├── ecommerce-gateway/            # WebFlux routing, rate limiting, request tracing
└── services/
    ├── identity-service/         # Account, addresses, JWT, refresh token, RBAC
    ├── catalog-service/          # Category, brand, SPU, SKU, price, product media
    ├── inventory-service/        # Warehouse, stock, reservation, ledger, Outbox
    ├── trade-service/            # Cart, order snapshots, recovery, event consumers
    ├── payment-service/          # Payment order, signed callbacks, transaction, Outbox
    ├── fulfillment-service/      # Picking, shipping, append-only logistics, Outbox
    └── marketing-service/        # Benefits, pricing locks, regional rules, allocation
```

`platform-common` must not contain database entities or domain rules. Services
communicate through API/event contracts and never share mapper classes.

## Build

```powershell
cd C:\Users\lenovo\Desktop\ecommerce-platform\backend
mvn clean verify
```

## Identity API

All browser and admin clients call these routes through the gateway:

| Method | Path | Authentication |
| --- | --- | --- |
| `POST` | `/api/v1/identity/auth/register` | Public |
| `POST` | `/api/v1/identity/auth/login` | Public |
| `POST` | `/api/v1/identity/auth/refresh` | Refresh token |
| `POST` | `/api/v1/identity/auth/logout` | Refresh token |
| `GET` | `/api/v1/identity/me` | Bearer access token |
| `POST/GET` | `/api/v1/identity/addresses` | Bearer access token |
| `PUT/DELETE` | `/api/v1/identity/addresses/{addressId}` | Address owner |
| `POST` | `/api/v1/identity/addresses/{addressId}/default` | Address owner |
| `GET` | `/api/v1/identity/status` | Public |

Passwords use BCrypt. Access tokens expire after 15 minutes. Opaque refresh
tokens expire after seven days, are stored only as SHA-256 hashes, and rotate on
every use. Reusing a rotated or logged-out token returns `401`.

The first address becomes the default automatically. Each account can store at
most 20 addresses, and mutations are serialized on the account row so concurrent
default changes cannot leave multiple defaults. Trade reads an owned address only
through the protected `/api/v1/identity/internal/**` API.

Login failures are counted by normalized email. Five failures within 15 minutes
lock that identifier for 30 minutes. The gateway independently limits login,
registration, and refresh traffic by client IP. Both controls use Redis Lua
scripts in normal operation and bounded local Caffeine state when Redis is down.

## Catalog API

Public reads use `/api/v1/catalog/categories`, `/brands`, `/products`, and
`/products/{id}`. Only products in `ACTIVE` state are visible. Administrative
writes are under `/api/v1/catalog/admin/**` and require an `ADMIN` or `OPERATOR`
role from the identity JWT.

Product creation writes an SPU and at least one SKU in one MySQL transaction.
SKU prices use `DECIMAL(18,2)` / `BigDecimal`; catalog does not own inventory.
Publishing and updates use optimistic versions. Product images use a private
MinIO bucket: request a pre-signed PUT URL, upload directly, then confirm the
object so its metadata is persisted. A MinIO signing outage omits media URLs but
does not make text catalog reads fail.

## Inventory API

Public stock summaries use `/api/v1/inventory/stocks/{skuId}`. Warehouse and
stock-adjustment commands are under `/api/v1/inventory/admin/**` and require an
`ADMIN` or `WAREHOUSE` role. Reservation commands are under
`/api/v1/inventory/internal/**`; they require the trusted backend service identity
and are blocked at the public gateway.

MySQL is the inventory source of truth. Reservation uses conditional updates,
never read-then-write stock deduction. `reservationNo` and adjustment movement
numbers are idempotency keys backed by unique indexes and request hashes.
Confirming reduces both `on_hand` and `reserved`; releasing or expiring reduces
only `reserved`. Every mutation writes an immutable stock movement and an
Outbox event in the same local transaction. RocketMQ failure leaves the event
pending for retry and does not roll back the stock transaction.

## Trade API

Authenticated customers use `/api/v1/trade/cart/items` and
`/api/v1/trade/orders`. Every create-order request requires an
`Idempotency-Key` and `addressId`. Trade reads the current catalog and owned
identity address, persists product/SKU/price/address snapshots, creates
`PENDING_STOCK`, then calls inventory with a stable
reservation number. A successful reservation moves the order to
`PENDING_PAYMENT`; shortage closes it without pretending that an order can be
paid.

Remote calls are outside MySQL transactions. Inventory outages leave the order
in `PENDING_STOCK`; cancellation outages leave it in `CANCELING`. A scheduled
recovery job retries both operations. Payment timeout uses the same cancellation
path, so release remains idempotent and auditable. Every state change writes
history and a trade Outbox event in the same local transaction.

## Payment API

Authenticated customers create and read payments under `/api/v1/payment/payments`.
Creation requires `Idempotency-Key`; payment verifies the order through a protected
trade-service internal endpoint and never reads the trade database. The local mock
channel callback is public because a real provider cannot present a customer JWT,
but every callback must pass HMAC-SHA256 verification and a five-minute timestamp
window. Callback event IDs, payment transactions, and payment success events are
uniquely persisted so duplicate delivery has no duplicate financial side effect.

Payment success is not a synchronous cross-service transaction. `payment-service`
writes `PaymentSucceeded` to its Outbox, `trade-service` consumes it in the same
transaction as `PENDING_PAYMENT -> PAID`, then publishes `OrderPaid`. Inventory
consumes `OrderPaid` and confirms the reservation. Both consumers persist
`consumed_event` with their local business update and reconnect if RocketMQ is
temporarily unavailable.

## Fulfillment API

`fulfillment-service` consumes `OrderPaid` and creates exactly one fulfillment
order per trade order. The event carries the immutable delivery snapshot, so
later identity-address edits or deletion cannot change an existing shipment.
Customers read only their own logistics through
`/api/v1/fulfillment/orders`. Warehouse and administrator commands under
`/api/v1/fulfillment/admin/**` advance the explicit picking, packing, shipping,
transit, delivery, and signed state machine. Carrier event identifiers and
tracking numbers are unique; logistics traces are append-only.

Fulfillment writes lifecycle facts to `ecommerce-logistics-events`. Trade
consumes `FulfillmentCreated`, `ShipmentDispatched`, and `ShipmentSigned` in
local transactions to advance `PAID -> FULFILLING -> SHIPPED -> COMPLETED`.
MQ failure leaves Outbox rows pending and never fabricates an order state.

## Marketing price API

`marketing-service` owns fixed-amount coupon, red-packet, and subsidy rules.
An order may use at most one benefit of each type and may stack all three.
Rules can be nationwide or limited by six-digit province, city, or district
codes. Trade locks pricing before inventory reservation and persists the
returned totals, per-item payable amounts, and benefit-to-line allocations as
immutable snapshots.

The marketing lock is idempotent by order number. `OrderCanceled` and
`OrderClosed` release locked benefits; `OrderPaid` redeems them. Lifecycle
events are deduplicated in the same local transaction as the state change.

## Real foundation smoke test

Start the middleware, initialize its resources, and run:

```powershell
../deploy/docker/bootstrap-resources.ps1
./run-foundation-smoke.ps1
```

The script loads ignored local credentials from `deploy/docker/.env`, packages
the backend, starts identity, catalog, inventory, marketing, trade, payment, fulfillment, and gateway in hidden
processes, and uses real MySQL, Nacos, Redis, RocketMQ, and MinIO containers. In
addition to the identity and degradation checks, it proves catalog RBAC, draft
isolation, product publication, pre-signed image upload/download, inventory
RBAC, 100-request stock competition, reservation idempotency, confirm/release,
and Outbox publication. It also runs 30 real orders against five units of stock,
checks address ownership, address edit/delete isolation, order snapshots,
idempotency/cancellation, verifies a signed duplicate
payment callback, and waits for `PaymentSucceeded -> OrderPaid -> inventory
confirmation / fulfillment creation`. It then executes picking, packing,
shipping, three logistics nodes, signing, and verifies the trade order becomes
`COMPLETED`. Temporary database rows, objects, Redis keys, and
processes are removed in `finally`. Runtime logs are written to the ignored
`backend/.run` directory.

Default local endpoints:

- Gateway: `http://127.0.0.1:18000`
- Identity service: `http://127.0.0.1:18101`
- Catalog service: `http://127.0.0.1:18102`
- Inventory service: `http://127.0.0.1:18103`
- Trade service: `http://127.0.0.1:18104`
- Payment service: `http://127.0.0.1:18105`
- Fulfillment service: `http://127.0.0.1:18106`
- Marketing service: `http://127.0.0.1:18107`
- Routed status: `http://127.0.0.1:18000/api/v1/identity/status`

Do not expose service ports to browser clients in production; only the gateway
is a public application entry point.
