# API reference

Every API is reached through one of the two edges, never a service directly:

| Edge | Address (DEV) | Serves |
|---|---|---|
| `storefront-web` | `http://localhost:8081` | the shop and its back office: `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/admin/**` (identity), `/api/v1/catalog/**`, `/api/v1/orders/**`, `/api/v1/backoffice/**` |
| `ops-web` | `http://localhost:8082` | the ops console: `/api/v1/auth/**` (identity, for signing in), `/api/v1/ops/**` |

Accounts, sign-in, two-factor, passkeys and sessions are identity's (Bastion's)
API, documented in [identity/api.md](identity/api.md). Everything below is the
rest of the system.

## Conventions

- **Authentication.** Sign in through `POST /api/v1/auth/login` (identity) and
  send the access token as `Authorization: Bearer <token>`. The edge checks it
  with identity on every call and forwards the caller's id, email, role and
  whether the session used a second factor; services never see the token.
  Without a valid token, an authenticated endpoint answers `401`.
- **Ownership.** A shopper's data is scoped by their user id: another user's
  order is `404`, never `403`.
- **Administrators.** `/api/v1/backoffice/**` and `/api/v1/ops/**` require role
  `ADMIN` (checked by the edge and again by the service) and, where the
  environment requires it (`ADMIN_REQUIRES_MFA` for the back office, `OPS_REQUIRE_MFA`
  for ops; on unless DEV's settings turn them off), a two-factor session.
- **Errors** are `{"message": "...", "code"?: "...", "fields"?: {...}}`. Every
  answer, error or not, carries `X-Request-Id`; quote it when reporting a problem
  (it finds the request's logs and trace).
- **Lists** are paged: `?page=0&size=20` (at most 50) → `{items, page, size, total}`.
- **Overload.** A service that cannot take more work, or has lost its database
  for a moment, answers `503` with `Retry-After` (ADR-030). Retry after that long.
- **Money** is integer cents plus an ISO-4217 currency; **times** are UTC ISO-8601.

## Catalog — public

| Endpoint | Notes |
|---|---|
| `GET /api/v1/catalog/products?category&q&page&size` | Active products, with `available` stock. Cached for 5 s at the edge and in the service (`Cache-Control: public, max-age=5`; `X-Cache-Status` says which). While every catalog instance is down the edge serves its cached copy |
| `GET /api/v1/catalog/products/{sku}` | One active product; `404` if unknown or inactive |
| `GET /api/v1/catalog/categories` | The categories that have active products |

## Orders — signed in

| Endpoint | Notes |
|---|---|
| `POST /api/v1/orders` | Body `{lines: [{sku, quantity}], shippingAddress, paymentMethod, expectedTotalCents}`; header `Idempotency-Key` required (repeat it to retry safely: the same order comes back). `202 {id, status}`: the order is placed and proceeds asynchronously (ADR-009). `400` unknown or inactive SKU, bad quantity; `409 PRICE_CHANGED` with the current lines when a price moved since the cart was shown; `503` + `Retry-After` when shedding load |
| `GET /api/v1/orders?page&size` | The caller's orders, newest first |
| `GET /api/v1/orders/{id}` | With lines and status history; `404` if not the caller's |
| `POST /api/v1/orders/{id}/cancel` | `409 NOT_CANCELLABLE` once shipped or finished; a paid order is refunded |
| `POST /api/v1/orders/{id}/return` | Body `{reason}`; `409 NOT_RETURNABLE` unless delivered and within the return window |

Order statuses: `PENDING → AWAITING_PAYMENT → CONFIRMED → SHIPPED → DELIVERED`,
then `RETURN_REQUESTED → RETURNED → REFUNDED`; or `CANCELLED` with a reason
(`OUT_OF_STOCK`, `PAYMENT_DECLINED`, `PAYMENT_UNAVAILABLE`, `EXPIRED`, `TIMEOUT`, `CUSTOMER`).
`paymentMethod` is an opaque token from the payment provider; in DEV the fixed
test tokens are listed in [architecture §15](architecture.md#15-known-limits).

## Back office — administrators

Read-only except products and stock. Changes are written to an audit log.

| Endpoint | Notes |
|---|---|
| `GET /api/v1/backoffice/catalog/products` | All products, inactive included |
| `PUT /api/v1/backoffice/catalog/products/{sku}` | Create or change a product `{name, description, category, priceCents, currency, active}`; announces `product.upserted` |
| `GET /api/v1/backoffice/inventory/stock` | Stock per SKU: on hand, reserved, available |
| `PUT /api/v1/backoffice/inventory/stock/{sku}` | Body `{onHand}`; announces `stock-changed` |
| `GET /api/v1/backoffice/inventory/reservations?orderId` | Reservations and their state |
| `GET /api/v1/backoffice/orders?q&status&page&size` | Order lookup by id or email |
| `GET /api/v1/backoffice/orders/{id}` | One order with its history |
| `GET /api/v1/backoffice/billing/payments?orderId\|from&to` | Payments and refunds |
| `GET /api/v1/backoffice/shipping/shipments?orderId` | Shipments and their steps (no addresses) |
| `GET /api/v1/backoffice/notifications?recipient\|from&to` | Emails sent: template, recipient, state (no bodies) |

The reservations, payments, shipments and notifications endpoints exist for
support and for the resilience experiments' ledger audit (ADR-028).

## Ops — administrators, through `ops-web`

| Endpoint | Notes |
|---|---|
| `GET /api/v1/ops/config` | Environment, which DEV features exist (`testkit`, `instanceControl`, `experiments`), whether replay needs two-factor (`replayRequiresMfa`), the services, Grafana's address |
| `GET /api/v1/ops/overview` | Per service: instances up, request rate, error rate, p95, consumer lag; system totals; firing incidents |
| `GET /api/v1/ops/services/{name}` | One service in detail |
| `GET /api/v1/ops/services/{name}/series?metric&range` | A time series; `metric` is one of a fixed set of keys (never PromQL from the client) |
| `GET /api/v1/ops/services/{name}/errors` | Its last 50 error log lines (Loki) |
| `GET /api/v1/ops/business` and `…/business/series?metric&range` | Funnel, cancellation reasons, revenue, saga timings, stock |
| `GET /api/v1/ops/kafka` | Brokers, topics, consumer groups and their lag |
| `GET /api/v1/ops/kafka/dead-letters/{topic}?limit` | The newest dead-lettered records of a `.dlt` topic, with the failure |
| `POST /api/v1/ops/kafka/dead-letters/{topic}/replay` | Body `{partition, offset}`: republish that record to its original topic. Needs a two-factor session where `OPS_REQUIRE_MFA` is on; written to the incident log |
| `GET /api/v1/ops/incidents?status&page&size` | Incidents raised by alerts and by operator actions |
| `GET /api/v1/ops/incidents/{id}` | One incident with its timeline |
| `POST /api/v1/ops/incidents/{id}/ack` | Acknowledge |
| `POST /api/v1/ops/incidents/{id}/notes` | Body `{note}` (at most 4 000 characters) |
| `GET /api/v1/ops/live` | Server-Sent Events: events per type per second and the latest event headers (ADR-023) |
| `GET /api/v1/ops/instances` | Every service's instances, every broker, every consumer group's members; in DEV merged with the controller's container states |

### DEV only

These answer `404` in INT, QA and PROD, where the controller and the testkit do
not exist (ADR-014).

| Endpoint | Notes |
|---|---|
| `POST /api/v1/ops/instances/{id}/{action}` | `stop`, `start`, `kill`, `pause`, `unpause`, `restart` one container of this deployment (ADR-027); the controller refuses itself, the Docker proxy, ops, the console edge and the observability stack. Written to the incident log |
| `GET /api/v1/ops/experiments` | The experiment catalogue, each with its hypothesis and last verdict |
| `POST /api/v1/ops/experiments/{name}/run` | Queue one experiment; they run one at a time |
| `POST /api/v1/ops/experiments/run` | Body `{names: [...]}` (at most 100), or no names for the whole catalogue |
| `GET /api/v1/ops/experiments/runs` | Every run: verdict (`RUNNING` while queued or running, `PASSED`, `FAILED`, `NOT_RUN` when the system was not in steady state, `NOT_APPLICABLE` when it needs the HA topology), checks, timeline |
| `GET /api/v1/ops/experiments/runs/{id}` | One run |
| `GET /api/v1/ops/experiments/runs/{id}/report` | The run as Markdown (what `scripts/prove.sh` saves to `build/proofs/`) |
| `/api/v1/ops/testkit/**` | The testkit, proxied. Every simulation is off until switched on and the switches are remembered (ADR-033): `GET status` (with `adminReady`, `testData`, `seeded`, `switches`); `GET switches`, `PUT switches {testData, shoppers, actors, faults}` (the whole state: what is on and not listed goes off); `PUT test-data {on}`; `PUT shoppers {count, ordersPerMinute, cancelRate, returnRate, declineRate, outOfStockRate}`; `GET actors`, `POST actors {kind, type, topic, ratePerSecond}`, `DELETE actors/{id}`, `DELETE actors`; `GET faults`, `PUT faults/{payment\|carrier} {outage, latencyMs, errorRate}`; `GET journal` |

## Internal

Not reachable through either edge (they answer `404` for `/internal/**` and
`/actuator/**`):

- `GET /internal/authz[?require=admin]` — identity, asked by the edges on every
  call ([identity/api.md](identity/api.md)).
- `POST /internal/alerts` — ops, Alertmanager's webhook, on the telemetry network only.
- `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` — every service,
  for Docker's healthchecks and Prometheus.
