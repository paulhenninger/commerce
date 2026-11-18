# Operations: discovering, alerting, tracking and debugging issues

> The operating model, refreshed with what the resilience experiments observed
> on the DEV HA topology (section 8, milestone 9b). Alert thresholds are still
> starting points, to be replaced by measured values (implementation plan,
> milestone 11). Identity's own
> operations guide — keys, production checklist, incident response for accounts —
> is [identity/operations.md](identity/operations.md).

## 1. How an issue is discovered

| Source | Catches | Where it shows |
|---|---|---|
| **Symptom alerts** — SLO burn rate, synthetic probes | Customers are affected now | Incident (critical) + page |
| **Cause alerts** — lag, dead letters, outbox age, breaker, saturation | Something is degrading and will affect customers | Incident (warning) |
| **Ops console** — Overview and live feed | Anything visibly off: a red tile, a stalled funnel, a growing dead-letter count | Console |
| **Customer report** | What monitoring missed | Support quotes the **request id** shown on the error |
| **Deploy checks** — health wait, smoke test | A bad release | CD run |

## 2. Service level objectives

| SLO | Objective | Measured by |
|---|---|---|
| Storefront API availability | 99.9% of requests are not 5xx, over 30 days | `http_server_requests_seconds_count` at catalog, order, identity |
| Catalog latency | 99% of reads under 250 ms | histogram buckets |
| Checkout latency | 99% of `POST /orders` under 500 ms | histogram buckets |
| Order completion | 99% of placed orders settle (confirmed or cancelled) within 30 s | `commerce_order_saga_seconds` |
| Site reachable | Probes of both edges succeed 99.9% | `commerce_probe_success` |

Availability and latency SLOs alert on **burn rate** with two windows (fast:
14.4× over 5 min and 1 h → critical; slow: 6× over 30 min and 6 h → warning), so
a brief blip does not page and a slow leak is still caught.

## 3. Alert rules

All rules carry `severity`, `service`, `summary`, `description` and
`runbook_url` (an anchor in this document). Files:
`deploy/observability/prometheus/rules/*.yml`.

| Alert | Fires when | Severity | Runbook |
|---|---|---|---|
| `ApiAvailabilityBurnFast` / `Slow` | Error budget burning at 14.4× / 6× | critical / warning | [5xx](#runbook-elevated-5xx) |
| `ApiLatencyBurnFast` / `Slow` | Latency budget burning | critical / warning | [latency](#runbook-high-latency) |
| `SiteProbeFailing` | An edge probe fails for 1 min | critical | [site down](#runbook-site-unreachable) |
| `ServiceDown` | A service has no healthy replica for 1 min | critical | [service down](#runbook-service-down) |
| `ServiceReplicaDown` | Fewer healthy replicas than expected for 5 min | warning | [service down](#runbook-service-down) |
| `OrdersStuck` | Orders in `PENDING`/`AWAITING_PAYMENT` older than twice the saga timeout | critical | [stuck orders](#runbook-stuck-orders) |
| `OrderCancellationSpike` | Cancelled / placed over 10 min above 30% | warning | [cancellations](#runbook-cancellation-spike) |
| `ConsumerLagHigh` | A service's group lag above 1 000 for 5 min, or growing for 10 min | warning | [lag](#runbook-consumer-lag) |
| `DeadLettersArriving` | Any dead-letter topic grew in the last 5 min | warning | [dead letters](#runbook-dead-letters) |
| `DeadLetterFlood` | More than 100 dead letters in 5 min | critical | [dead letters](#runbook-dead-letters) |
| `OutboxBacklog` | Oldest outbox row older than 30 s for 2 min | critical | [outbox](#runbook-outbox-backlog) |
| `CircuitBreakerOpen` | A breaker is open for 1 min | warning | [external dependency](#runbook-external-dependency-down) |
| `PaymentFailuresHigh` | Payment failures above 20% over 10 min | warning | [external dependency](#runbook-external-dependency-down) |
| `NotificationsFailing` | Emails marked `FAILED`, or pending older than 15 min | warning | [email](#runbook-email-not-sending) |
| `KafkaBrokerDown` | Fewer brokers than expected | critical (warning with 3) | [Kafka](#runbook-kafka) |
| `KafkaUnderReplicated` | Under-replicated partitions for 5 min | warning | [Kafka](#runbook-kafka) |
| `DbPoolSaturated` | Hikari pending threads > 0 for 2 min | warning | [database](#runbook-database) |
| `MySqlDown` / `MySqlConnectionsHigh` | Exporter cannot reach MySQL / connections above 80% | critical / warning | [database](#runbook-database) |
| `JvmMemoryHigh` | Heap above 90% after GC for 5 min | warning | [saturation](#runbook-saturation) |
| `ContainerRestarting` | A service restarted more than twice in 10 min | warning | [service down](#runbook-service-down) |
| `EdgeRateLimiting` | Sustained 429s at an edge | info | [abuse](#runbook-abuse-or-flood) |
| `AuthFailuresSpike` | Sign-in failures or lockouts far above baseline | warning | [identity operations](identity/operations.md) |
| `ContractViolations` (dev) | The testkit's contract tap sees an invalid event from a real service | warning | [dead letters](#runbook-dead-letters) |

**As built (milestone 5).** The rules are in four files (`services.yml`,
`slo.yml`, `business.yml`, `messaging.yml`), all passing `promtool check rules` (CI will run it from milestone 10).
- `ServiceDown` has one rule per service: targets are found through Docker DNS, so a
  service with no running instance has no `up` series at all, and only `absent()`
  can notice. It fires about 80 s after the last instance stops
  (`scripts/verify-observability.py` measures it).
- `ServiceReplicaDown` compares the healthy count with the most seen in the past hour.
- `ContainerRestarting` counts changes of `process_start_time_seconds`.
- `SiteProbeFailing`, `ConsumerLagHigh`, `KafkaBrokerDown` and `KafkaUnderReplicated`
  use gauges the ops service exports (milestone 6); `ContractViolations` uses the
  testkit's (milestone 7). Until then they cannot fire.
- `DeadLettersArriving` and `DeadLetterFlood` use the consumers' own
  `commerce_events_dead_lettered_total`.
- `EdgeRateLimiting` is **not implemented**: the nginx edges export no metrics yet.
  Their 429s are in their access logs (Loki: `{service="storefront-web"} |= " 429 "`).

Routing (Alertmanager): grouped by `alertname` and `service`, 10 s group wait,
repeat every 4 h. Every alert goes to the ops service. `critical` additionally
goes to the on-call receiver, `warning` to the team channel — configured per
environment (`ALERT_*` variables); in dev both are Mailpit. Inhibition:
`ServiceDown` silences that service's lag, latency and replica alerts;
`KafkaBrokerDown` (all brokers) silences lag and outbox alerts.

## 4. How an issue is tracked

1. Alertmanager posts the alert to the ops service, which opens an **incident**
   (or updates it) — visible on the console's Incidents page and as a badge on
   the affected service's tile.
2. Someone **acknowledges** it: their name and the time are recorded, and
   everyone else can see it is being handled.
3. They add **notes** as they go: what they saw, what they did. Dead-letter
   replays add a note automatically.
4. When the condition clears, Alertmanager sends *resolved* and the incident
   closes itself, keeping its timeline.
5. Incidents are kept for 90 days. Anything that paged gets a short review:
   cause, detection time, what would have caught it sooner. Recurring causes
   become a ticket in the issue tracker, linked from the incident note.

The incident list is the operational record; the issue tracker (GitHub Issues)
holds the follow-up work.

## 5. How an issue is debugged

### The ids

| Id | Where to find it | Leads to |
|---|---|---|
| **Request id** (`X-Request-Id`) | On every response; shown to the customer on errors; in edge access logs | Every log line of that request |
| **Trace id** (`X-Trace-Id`) | On every response; in every log line as `traceId` | The whole flow in Tempo: each service, each Kafka hop, timings |
| **Order id** | In the URL of the order page; in logs as `orderId` | Order history (back office), all events for the order, all log lines |
| **Event id** | In the envelope; in logs as `eventId`; on dead letters | Which consumer processed, skipped or rejected it |

### From a customer complaint

1. Get the request id (on their error message) or the order id.
2. Grafana → Explore → Loki: `{environment="prod"} | json | requestId="…"` (or
   `orderId="…"`). Read the lines in time order; take the `traceId`.
3. Open the trace (the log line links to it). The slow or failed span names the
   service and operation; its logs are one click away.
4. For an order: back office → Orders → the order's history shows each state
   change and why.

### From an alert

1. Open the incident; follow its runbook link below.
2. Console → the affected service: rate, errors, latency, lag, outbox, breaker,
   recent error lines — each with a link into Grafana for the same time range.
3. Narrow down by the ids above.

### Useful queries

```logql
# Everything about one order, across services
{environment="dev"} | json | orderId="01JD…"

# Errors of one service
{service="billing"} | json | log_level="ERROR"

# Why records were dead-lettered
{environment="dev"} |= "dead-letter topic" | json
```

```promql
# Error ratio per service
sum by (application) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
  / sum by (application) (rate(http_server_requests_seconds_count[5m]))

# p95 latency per endpoint of one service
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="order"}[5m])))

# What each consumer is doing with events
sum by (consumer, type, outcome) (rate(commerce_events_consumed_total[1m]))

# Lag per group
max by (group, topic) (commerce_kafka_consumer_lag)
```

```bash
# Tail one service, pretty
scripts/stack.sh dev logs --no-log-prefix -f order | jq -r '[.["@timestamp"], .log.level, .message] | @tsv'

# Read a dead-letter topic by hand
# (as the ops principal: write its credentials to a client properties file first;
# see scripts/smoke.sh for how)
scripts/stack.sh dev exec kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic commerce.order.events.v1.dlt \
  --from-beginning --property print.headers=true --max-messages 10
```

## 6. Runbooks

Each: what it means, how to confirm, what to do.

### Runbook: elevated 5xx

Customers are getting errors. Console → Overview: which service's error rate is
up? Its detail page → recent errors. One endpoint or all? All endpoints of one
service usually means its database (see [database](#runbook-database)) or a bad
deploy — roll back to the previous image tag. One endpoint: read the trace of a
failing request.

### Runbook: high latency

Console → service detail: is the DB pool saturated, is heap near the limit, is
one endpoint slow? Trace a slow request: the long span is the cause. If the
auth check to identity is the long span, identity is the bottleneck — scale it.

### Runbook: site unreachable

The probe cannot load an edge. `scripts/stack.sh <env> ps`: is the edge
container healthy? Its logs? If the edge is up but returns 5xx, treat as
[elevated 5xx](#runbook-elevated-5xx). Check the load balancer/TLS in front.

### Runbook: service down

`scripts/stack.sh <env> ps` and the container's last log lines. Startup refusals
are explicit: `TestToolingGuard`, `DeploymentSafetyCheck`, missing secret,
migration failure. An out-of-memory exit shows as exit code 137 or
`ExitOnOutOfMemoryError`. While a domain service is down its events wait in
Kafka; nothing is lost. After restart, watch its lag drain on the Kafka page.

### Runbook: stuck orders

Orders are not settling and the timeout is not clearing them. Is the order
service healthy and consuming (lag on `inventory`, `billing` topics)? Is its
outbox draining? If the order service itself was down, they clear after it
returns. Individual order: back office → history shows the last step reached.

### Runbook: cancellation spike

Console → Business: cancellations by reason. `OUT_OF_STOCK` → stock levels (a
popular product ran out). `PAYMENT_DECLINED` → provider-side or a fraud pattern.
`PAYMENT_UNAVAILABLE` → [external dependency](#runbook-external-dependency-down).
`TIMEOUT` → a service in the saga is down or lagging.

### Runbook: consumer lag

Kafka page: which group, which topic? Is the group's service up, with all
replicas? Is it slow (handler latency up → its database or an external call) or
flooded (write rate on the topic far above normal → find the source on the live
feed; see [abuse](#runbook-abuse-or-flood))? Lag on a group that belongs to no
service is someone else's consumer and harms nobody.

### Runbook: dead letters

Kafka page → dead letters: read the reason header.
- **Contract failure / malformed** (`InvalidEventException`): a producer is
  writing bad events. The `source` field and the topic's owner say who. If it is
  a real service, that is a bug in its latest release — roll it back. If the
  source is unknown, something that should not have access is writing to the
  topic.
- **Handler failure**: the consumer has a bug or its dependency was down. Fix or
  wait for recovery, then **replay** the dead letters from the console. Replay
  is safe to repeat (consumers are idempotent).

### Runbook: outbox backlog

A service cannot publish. Is Kafka up ([Kafka](#runbook-kafka))? The service's
logs show the relay's send errors. Requests keep working meanwhile and events
keep their order; the backlog drains by itself when publishing resumes. If Kafka
is healthy and one service's outbox is stuck, restart that service (the relay
lock is released when its connection closes).

### Runbook: external dependency down

A breaker is open: the payment provider or carrier is failing. Confirm with
`commerce_external_calls_seconds` by outcome and the provider's status page.
Payment: new orders are being cancelled with `PAYMENT_UNAVAILABLE` and nobody
is charged — decide whether to put up a banner. Refunds are not lost: they stay
`REFUND_PENDING` and `RefundWorker` retries them until the provider takes them
(ADR-031); a refund the provider answers `404` for (a charge it no longer knows)
stays queued and needs a person. Carrier: shipments queue and resume by themselves.
The breaker closes by itself when calls succeed again. In dev, check the chaos
lab — someone may have injected the fault.

### Runbook: email not sending

Notification service detail: SMTP errors in its logs. Pending emails are retried
with backoff (1 min doubling to 1 h, `FAILED` after 30 attempts, about a day), so
a short relay outage needs no action. `FAILED` rows can be reset to
`PENDING` once the relay is back.

### Runbook: Kafka

One of three brokers down: the system keeps working (observed: no failed
checkout through a broker kill); restart it and wait for under-replicated
partitions to return to zero before touching another. All brokers down:
requests still succeed (observed for 30 s: no failed checkout), events queue in
outboxes (at most ~240), sagas pause; past the saga timeout (2 min) new orders
are cancelled as `TIMEOUT`. Restore Kafka; outboxes drain in order — observed:
everything settled within about a minute of the brokers returning.

### Runbook: database

MySQL down: requests that need the database answer `503` with `Retry-After`
(never another 5xx); event handlers keep retrying the same record until the
database returns and never dead-letter it for an infrastructure failure
(ADR-030). Restore it; services reconnect by themselves — observed: orders flow
again within seconds of MySQL being healthy, and everything in flight settles
within about 30 s, with a clean ledger. Pool saturation in one service:
slow queries or a held transaction — look at that service's slowest traces.
Connections high: a replica count was raised without resizing pools
(replicas × 10 per service must stay under `max_connections`).

### Runbook: saturation

Heap high: look for a traffic change or a leak (does it recover after GC?).
Short term: restart the replica (the other serves) or add a replica. Raise the
memory limit only with evidence.

### Runbook: abuse or flood

429s at the edge mean the limits are working. Identify the source from the edge
access log (client address) and block it upstream if it persists. A flood on a
topic: the live feed shows the type and `source`; the topic's consumers skip or
dead-letter what is not real, and lag recovers once it stops. A writer with no
right to a topic is refused by the broker (ADR-024); a flood from a service's
own principal means that service is misbehaving — stop its instances from the
console (dev) or roll it back.

## 7. Routine operations

| Task | How |
|---|---|
| Start / stop an environment | `scripts/stack.sh <env> up -d --wait` / `down` |
| Deploy a version | CD (`cd.yml`): every push to main reaches the environments listed in `CD_ENVIRONMENTS`; prod from a `v*.*.*` tag after its reviewers approve. By hand on the host: `scripts/deploy.sh <env> <tag>` |
| Roll back | Automatic: `deploy.sh` returns to the last tag that came up healthy (`deploy/env/.<env>.deployed`) when a release fails its health wait or, outside DEV, `scripts/smoke-clean.sh`. By hand: `scripts/deploy.sh <env> <previous tag>`. Migrations are expand-then-contract, so the previous version runs on the newer schema |
| Check a clean environment | `scripts/smoke-clean.sh <env>` on its host (reads only; no test data needed) |
| Check the hardening (dev) | `scripts/verify-stack.sh` against the running DEV stack (CI runs it on every change) |
| Scale a service | `deploy.replicas` in the environment's overlay (keep pools × replicas under MySQL's limit) |
| Replay dead letters | Console → Kafka → Dead letters → Replay |
| Rotate secrets | `JWT_SECRET`, `DATA_ENCRYPTION_KEY`: see identity operations. `EDGE_SHARED_SECRET`: update edges and services together (restart) |
| Stop, kill, pause or restart an instance (dev) | Console → Instances |
| Prove resilience (dev) | Console → Resilience proofs, or `scripts/prove.sh` |
| Back up | MySQL: logical dump per database plus binlogs; Kafka is not a system of record (state is in the databases; the outbox is transient) |

## 8. Observed behaviour

What the resilience catalogue measured on the DEV HA topology (two instances of
each service, three brokers, 20 simulated shoppers), full run of 2026-11-17, all
23 experiments passed with a clean ledger audit. Reports: `build/proofs/` after
`scripts/prove.sh`, or the console's Resilience proofs page.

| Failure | Customers saw | Recovery |
|---|---|---|
| One instance of any service killed, or hung for 30 s | No failed checkout | The other instance carried on; Kafka moved the partitions; the hung one's work moved within the session timeout |
| Every instance of inventory, billing or shipping down 60 s | No failed checkout (orders wait) | Waiting orders completed, or were cancelled with the right reason, within about 30 s of the service returning. Shipping was once down 20 min (the host slept): orders still completed |
| Every catalog instance down | No failed catalog request: the edge served its cache | — |
| One broker killed | No failed checkout | Leader election in seconds; settled within 30 s of its restart |
| All three brokers down 30 s | No failed checkout | At most ~240 events waited in outboxes; drained in order, settled within about a minute |
| MySQL down 20 s | Checkouts during the outage failed, all as clean `503` + `Retry-After` (32 of 51) | Settled within about 30 s of MySQL returning; no handler failure dead-lettered |
| Payment provider down | Orders cancelled promptly as `PAYMENT_UNAVAILABLE`; nobody charged | Breaker closed by itself |
| Carrier down | Orders still confirmed | Shipments resumed; settled within about a minute |
| Malformed and wrongly-shaped records | No failed checkout | Every bad record dead-lettered (thousands); no consumer stalled |
| Real events replayed byte for byte | No failed checkout | Each applied once (thousands skipped as duplicates) |
| A flood on a topic | No failed checkout | Lag peaked in the thousands and recovered |
| A client with no rights | Nothing | Every write, read and group join refused by the broker |
| Slow, crashing, flapping and stuck consumers | Nothing | Other groups' lag stayed at 0 |
| Every service's instances restarted one at a time under load | No failed checkout (304 checkouts) | — |

Operational lessons from running it:

- **Memory.** The HA DEV stack uses about 6.8 GiB at rest; under the
  experiments' load it once reached Docker Desktop's 8 GB limit and the Docker
  engine stopped answering until Docker Desktop was restarted. Give it more than
  8 GB, or use `--lite`.
- **Clean starts.** A crashed run can leave damage a later run trips over (a
  dead-lettered refund, leaked reservations). Reset DEV
  (`scripts/stack.sh dev down -v`) before a full run.
- **Host sleep.** When a laptop sleeps the clock jumps; the experiments re-check
  once after a deadline, so a run that spans a sleep still gives a fair verdict.
- **Two lines of defence for once-only effects.** With the processed-events
  check removed in a scratch build, the duplicate-storm experiment failed — and
  the ledger still held, because each service's own state checks ignored the
  replays. The experiment proves the first line, the audit the second.
