# Design review

A critical read of the first draft of the design, done before development
(2026-11-16). Part 1 lists the weaknesses found and already fixed in
[architecture.md](architecture.md), [decisions.md](decisions.md) and
[implementation-plan.md](implementation-plan.md). Part 2 lists the decisions that
are still open because they trade scope, cost or a stated requirement, each with
a recommendation. Part 3 lists what was reviewed and deliberately left alone.

## 1. Improvements adopted

| # | Weakness in the first draft | Change |
|---|---|---|
| 1 | **Contradiction:** readiness included Kafka, so a Kafka outage would take services out of rotation — while the outbox design promised requests keep working without Kafka | Readiness is the database only; Kafka's state is reported and alerted on, never gates traffic |
| 2 | Services trusted `X-Auth-*` headers on network isolation alone | The edge also sends a shared `X-Edge-Token`; services ignore identity headers without it |
| 3 | Outbox relay polled every 100 ms: up to ~0.6 s of added latency across a six-hop order flow | Relay is woken on commit (milliseconds); polling remains only as the recovery path |
| 4 | A billing service returning from an outage would charge, then refund, orders already cancelled by timeout | Payment requests carry `expiresAt`; billing refuses expired ones |
| 5 | `processed_events` grew forever | Nightly purge past topic retention |
| 6 | Notification sent SMTP inside the event transaction | Handlers record the email; a worker sends with backoff — same pattern as shipping |
| 7 | Simulated shoppers bypassed the edge and identity, so half the system would not be exercised or observed | Shoppers are real accounts going through the storefront edge; per-shopper client addresses via Bastion's trusted-proxy mechanism (dev only), no limit disabled (ADR-021) |
| 8 | "Real time" console trailed by a Prometheus scrape (5–15 s) | Live feed: an ops consumer group streamed over SSE (ADR-023) |
| 9 | A price change between cart and checkout silently charged a different amount | Client sends the expected total; mismatch → `409 PRICE_CHANGED` |
| 10 | Under overload, orders would queue and then time out en masse | Per-user order rate limit at the edge; load shedding (`503 Retry-After`) past a threshold of in-flight orders |
| 11 | INT/QA/PROD have no test data — and had no way to create products or stock | Back office for products, stock and order lookup (ADR-022) |
| 12 | Contracts enforced only by sharing code | Checked-in fixtures per event type; a test fails the build on an incompatible change |
| 13 | Alerts were static thresholds on internal signals; nothing measured the site from outside | SLOs with burn-rate alerts; synthetic probes of both edges |
| 14 | Dead letters could be seen but not acted on | Guarded, audited replay from the console |
| 15 | No rule for schema changes during a deploy with mixed versions | Expand-then-contract migrations, stated as a convention |
| 16 | "High availability" was only described for QA/PROD and could not be observed | `compose.ha.yml` (2 replicas, 3 brokers); after the owner's decision, the default for DEV, with instance controls and pass/fail experiments in the console (ADR-026, 027, 028) |

## 2. Open decisions

Each needs a yes/no from the project owner. Development can start without them;
the default is what the documents currently say.

| # | Question | Current design | Recommendation | Cost of changing |
|---|---|---|---|---|
| A | **Kafka authentication and ACLs** | — | **Decided 2026-11-16: yes.** SASL/SCRAM, a principal per service, least-privilege ACLs, in every environment (ADR-024) | — |
| B | **Simulators outside DEV** | — | **Decided 2026-11-16: no.** INT, QA and PROD require real provider endpoints and will not start without them (ADR-014) | — |
| C | **Cost of the per-request auth check** (ADR-007). Every authenticated call adds a hop to identity | No cache; identity replicated; hop budget p95 < 15 ms | Keep and measure at milestone 11. If it matters, first lever is a 2–5 s edge cache keyed on the token (revocation delayed by that long, which changes a Bastion guarantee and should be a conscious choice) | Small (edge config) |
| D | **Personal data across services** (ADR-025). Deleting an account leaves the email and address in orders, shipments, emails and 7 days of events | Not handled | Add: identity publishes `user.deleted`; services anonymise personal fields and keep the commercial record; data export gathers per-service data. Needed before real customers; not needed to meet the two stated goals | Moderate; touches Bastion's `AccountService` additively |
| E | **MySQL availability.** Single instance in every Compose file | Known limit | For real deployments use a managed or externally replicated MySQL. Inside Compose, Group Replication + MySQL Router (3 more containers, ~2 GB) is possible if demonstrating database failover in DEV is wanted | Large for the in-Compose option |
| F | **Rolling deploys.** Compose replaces a service's replicas together | Known limit | Accept for Compose. If zero-downtime releases on a single host matter, add a deploy script that replaces replicas one at a time; for real production, an orchestrator | Small (script) to large (orchestrator) |
| G | **Tracing at the edge.** Traces start at the first service; the edge contributes only the request id | Known limit | Try nginx's OpenTelemetry module in the edge image at milestone 5; adopt if the pinned unprivileged image supports it cleanly, else leave | Small, uncertain |
| H | **Secrets as files** rather than environment variables outside DEV | Environment variables (as Bastion) | Compose `secrets:` + Spring `configtree:` for INT/QA/PROD: secrets stay out of `docker inspect` and process listings | Small |
| I | **Partial returns and exchanges** | Whole-order returns only | Defer; the event model (lines on `order.return-requested`) already allows per-line returns later | Moderate |

## 3. Reviewed and kept

| Considered | Why it stays as designed |
|---|---|
| Schema registry with Avro/Protobuf instead of JSON + shared library | Fixtures give the compatibility check; JSON keeps events readable and forgeable for the chaos lab; revisit if repositories or teams split |
| Debezium CDC instead of a polling/woken relay | Same guarantees for far less infrastructure; the outbox table is CDC-compatible if needed later |
| Non-blocking retry topics | They reorder an order's events, which the saga depends on; in-place retries cost seconds once |
| Workflow engine for the saga | One flow does not justify the platform |
| Server-side carts | Adds a stateful service and sessions for little benefit at this scope |
| Search engine for the catalog | SQL is adequate for hundreds of products; the edge and in-process caches absorb read load |
| Order status over WebSocket/SSE | Polling one row for a few seconds is simpler and replica-safe |
| ~~Failure-drill button in the console~~ | **Reversed by the owner (2026-11-16):** the console controls instances, through a dev-only controller and a restricted Docker proxy (ADR-027) |
| Separate MySQL server per service in Compose | Separate databases and logins already enforce ownership; separate servers would cost ~4 GB |
| Kubernetes manifests | The brief is Docker Compose; images and configuration are orchestrator-neutral |
