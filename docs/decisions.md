# Architecture and design decisions

Each record: the situation, what was decided, what follows from it, and what was
rejected. They are settled unless marked **Open** — open ones are collected in
[design-review.md](design-review.md#open-decisions) with a recommendation.
Change a decision by adding a new record that supersedes it, not by editing history.

| # | Decision | Status |
|---|---|---|
| [001](#adr-001-microservices-one-database-per-service) | Microservices by business capability, one database per service | Accepted |
| [002](#adr-002-events-only-between-services) | Services integrate only through Kafka events | Accepted |
| [003](#adr-003-choreographed-saga-owned-by-the-order-service) | Choreographed saga, order state owned by the order service | Accepted |
| [004](#adr-004-transactional-outbox-and-idempotent-consumers) | Transactional outbox; idempotent consumers | Accepted |
| [005](#adr-005-retry-then-dead-letter) | Retry with backoff, then dead-letter; contract failures never retried | Accepted |
| [006](#adr-006-event-envelope-and-contracts) | One JSON envelope; contracts in a shared library with fixtures | Accepted |
| [007](#adr-007-authentication-at-the-edge) | Authentication at the edge, checked with identity on every request | Accepted |
| [008](#adr-008-identity-is-bastion) | Identity is Bastion, copied and renamed, controls intact | Accepted |
| [009](#adr-009-asynchronous-order-placement) | Order placement is asynchronous | Accepted |
| [010](#adr-010-data-other-services-need-arrives-as-events) | Data other services need arrives as events | Accepted |
| [011](#adr-011-observability-stack) | Logs → Loki, traces → Tempo, metrics → Prometheus, one Grafana | Accepted |
| [012](#adr-012-alerting-and-incident-tracking) | Alertmanager → ops service incidents | Accepted |
| [013](#adr-013-a-custom-ops-console) | A custom ops console beside Grafana | Accepted |
| [014](#adr-014-test-tooling-is-dev-only) | Test tooling is DEV-only, enforced three ways | Accepted |
| [015](#adr-015-external-dependencies) | External calls: timeouts, breaker, idempotency keys, outside transactions | Accepted |
| [016](#adr-016-hardened-containers-and-segmented-networks) | Hardened containers, segmented networks | Accepted |
| [017](#adr-017-one-build-one-dockerfile) | One Gradle build, one Dockerfile, one image per service | Accepted |
| [018](#adr-018-cicd) | CI on every change; CD promotes one set of images | Accepted |
| [019](#adr-019-technology-versions) | Technology and versions | Accepted |
| [020](#adr-020-plain-sql-for-domain-services) | Plain SQL (JdbcClient) and Flyway in domain services | Accepted |
| [021](#adr-021-simulated-shoppers-are-real-customers) | Simulated shoppers are real accounts going through the edge | Accepted |
| [022](#adr-022-back-office-in-the-storefront) | Back office for products, stock and order lookup | Accepted |
| [023](#adr-023-live-feed-over-server-sent-events) | Live feed over Server-Sent Events from an ops consumer group | Accepted |
| [024](#adr-024-kafka-authentication-and-acls) | Kafka clients authenticate; ACLs give each service least privilege | Accepted |
| [025](#adr-025-personal-data-across-services) | Personal data across services (deletion, export) | **Open** |
| [026](#adr-026-dev-runs-the-ha-topology-by-default) | DEV runs the HA topology by default | Accepted |
| [027](#adr-027-instance-control-from-the-console) | Instance control from the console through a narrow, dev-only path | Accepted |
| [028](#adr-028-resilience-is-proven-by-experiments-with-verdicts) | Resilience is proven by experiments with verdicts | Accepted |
| [029](#adr-029-bounded-password-hashing-in-identity) | Bounded password hashing in identity | Accepted |
| [030](#adr-030-infrastructure-failures-are-retried-not-dead-lettered) | Infrastructure failures are retried, not dead-lettered | Accepted |
| [031](#adr-031-refunds-are-queued-and-retried-until-made) | Refunds are queued and retried until made | Accepted |
| [032](#adr-032-a-public-repository) | The repository is public | Accepted |
| [033](#adr-033-dev-simulations-are-off-until-switched-on) | DEV simulations are off until switched on | Accepted (design; milestone 10a) |
| [034](#adr-034-the-load-baseline-is-measured-locally-github-is-optional) | The load baseline is measured locally; GitHub is optional | Accepted |
| [035](#adr-035-the-password-hashing-bound-is-set-per-environment) | The password-hashing bound is set per environment | Accepted (its heap sizing rule replaced by ADR-037) |
| [036](#adr-036-the-latency-targets-stay-this-machines-capacity-is-recorded-beside-them) | The latency targets stay; this machine's capacity is recorded beside them | Accepted (capacity filled in from the baseline of record) |
| [037](#adr-037-every-jvm-service-has-a-measured-memory-budget) | Every JVM service has a measured memory budget | Accepted |

---

## ADR-001: Microservices, one database per service

**Context.** The brief asks for distributed services (ordering, inventory,
billing, shipping …) that scale and fail independently.

**Decision.** One service per business capability: identity, catalog, order,
inventory, billing, shipping, notification, plus ops for monitoring. Each owns a
database nobody else reads or writes. In Compose they share one MySQL server but
have separate databases and separate least-privilege logins, so moving a database
to its own server is a configuration change.

**Consequences.** No cross-service joins or transactions; consistency between
services is eventual and achieved with sagas (003). Each service can be scaled,
deployed and reasoned about alone.

**Rejected.** A modular monolith — simpler and often the right first step, but
not what was asked for and it would not demonstrate independent failure. A shared
schema — couples every service to every table.

## ADR-002: Events only between services

**Context.** Synchronous calls between services multiply latency and turn one
service's outage into everyone's.

**Decision.** Services communicate only by publishing and consuming Kafka
events. No service calls another service's HTTP API. The only synchronous hops
are browser → edge → one service, and edge → identity for the auth check (007).

**Consequences.** A service being down delays work instead of failing requests.
Services need local copies of data they depend on (010). Flows are harder to see
in code, so tracing across Kafka (011) and the flow tables in the architecture
document matter.

**Rejected.** REST or gRPC between services with circuit breakers — availability
of a request becomes the product of its dependencies' availabilities. A mix
("events mostly") — the exceptions spread.

## ADR-003: Choreographed saga, owned by the order service

**Context.** Placing an order touches stock, payment and shipping, each in its
own database. It needs all-or-nothing behaviour without distributed transactions.

**Decision.** A saga in which each service reacts to events and emits its own.
The order service alone owns order state: it turns each outcome into the next
step (`inventory.reserved` → `order.payment-requested`; `payment.authorized` →
`order.confirmed`), cancels on failure or timeout, and every other service
compensates on `order.cancelled`. Other services consume only the order topic for
their triggers, never each other's topics.

**Consequences.** There is one place to read the order state machine and one
topic that orders each order's steps (same key), which removes the races between
"charge" and "cancel". The order service is the busiest consumer. Steps carry a
deadline so a late service does not act on a dead order.

**Rejected.** Pure peer-to-peer choreography (billing listens to inventory) —
shorter chain but creates ordering races between topics and spreads the flow
across services. A workflow engine (Temporal, Camunda) — robust, but a large new
piece of infrastructure for one flow. Two-phase commit — not available across
Kafka and MySQL, and it trades availability away.

## ADR-004: Transactional outbox and idempotent consumers

**Context.** "Update the database and publish an event" can fail between the
two, leaving them disagreeing. Kafka delivers at least once.

**Decision.** Publishing: write the event to an `outbox_events` table in the
same transaction as the state change; a relay per service publishes rows in
order. The relay is woken on commit and also polls; a MySQL advisory lock makes
one replica the publisher at a time. Consuming: record each event id in
`processed_events` in the same transaction as its effects, and skip ids already
there; make state changes conditional so they are also safe out of order.

**Consequences.** Effectively-once processing from at-least-once delivery, with
only MySQL and Kafka. A Kafka outage does not fail requests; events queue in the
outbox. One relay at a time caps publish throughput per service at one
connection's worth — thousands of events a second, far above need.

**Rejected.** Publishing directly after commit — loses events on a crash. Kafka
transactions — make Kafka-to-Kafka exactly-once, not database-to-Kafka. Change
data capture (Debezium) — the strongest version of the outbox, but adds Kafka
Connect and binlog configuration; the table design here is compatible with
moving to it later. `SELECT … FOR UPDATE SKIP LOCKED` relays on every replica —
higher throughput but breaks per-key ordering.

## ADR-005: Retry, then dead-letter

**Context.** One record a consumer cannot process must not stop the partition,
and must not vanish.

**Decision.** A failing handler is retried in place three times with exponential
backoff (200 ms, 800 ms, 3.2 s), then the record is published to `<topic>.dlt`
with failure details in headers and the offset is committed. A record that is not
a valid event, or whose payload breaks its contract, is dead-lettered at once.
Unknown event types and events that no longer apply are skipped, not failed.

**Consequences.** Poison records cost at most ~4 s of one partition once. Dead
letters are visible, alerted on, browsable and replayable from the ops console.
In-place retries delay the records behind the failing one on that partition by
the retry time. Amended by
[ADR-030](#adr-030-infrastructure-failures-are-retried-not-dead-lettered): a
failure of the service's own database is retried until it clears.

**Rejected.** Infinite retry — one bad record halts a partition. Skip and log —
loses data silently. Non-blocking retry topics — keep the partition moving during
retries but reorder an order's events, which the saga relies on.

## ADR-006: Event envelope and contracts

**Context.** Producers and consumers deploy independently and must not break
each other.

**Decision.** Every event is JSON in one envelope (`eventId, type, version,
occurredAt, source, aggregateId, payload`). Payload contracts are Java records in
`platform:core`, validated on deserialization. Consumers are tolerant readers:
unknown fields and unknown types are ignored. Changes are additive within a topic
version; a breaking change is a new topic (`.v2`). Each event type has checked-in
JSON fixtures (`platform/core/src/test/resources/contracts/<type>/*.json`), and a
test proves the current records still read every fixture ever added — so a
change that would break an older producer or consumer fails the build.

**Consequences.** Human-readable events, easy to debug and to forge for testing.
The shared library couples services at build time, acceptable in one repository.

**Rejected.** Avro or Protobuf with a schema registry — better compatibility
enforcement and smaller records, at the cost of another stateful service and
opaque payloads; the right move when teams or repositories split. Per-service
copies of the contracts — drift.

## ADR-007: Authentication at the edge

**Context.** Bastion's security rests on deciding authorisation from the
database on every request, so revocation is immediate. Seven more services now
need to know who is calling.

**Decision.** The edge (nginx `auth_request`) asks identity to validate the
access token on every authenticated request and forwards the verified identity
in `X-Auth-*` headers, plus a shared `X-Edge-Token`. Services accept identity
only with that token and only from the edge network. The check is not cached.

**Consequences.** Bastion's guarantees hold for every service; only identity
holds the signing key; services need no security framework. Every authenticated
request costs one extra hop and one indexed query, and identity must be up for
authenticated traffic — so identity runs replicated, and this hop is a measured
budget item (target p95 < 15 ms).

**Rejected.** Each service validating the JWT itself — spreads the shared HS256
secret to every service and loses immediate revocation. Asymmetric tokens with
local validation plus a revocation feed — fast, but revocation becomes eventual
and Bastion's token design changes. Caching the check for a few seconds at the
edge — a real latency win, kept as the first lever if measurement shows the hop
matters ([design review](design-review.md#open-decisions)).

## ADR-008: Identity is Bastion

**Context.** The brief: use the Bastion project for signup and sign-in.

**Decision.** Bastion is copied into `services/identity` and `web/storefront`
with its rename script (name `commerce`, package
`com.thirdstone.commerce.identity`). Changes to it are additive only: the
`/internal/authz` endpoint, OpenTelemetry tracing, and membership of the Gradle
multi-project build. Its rules stand: never weaken a control, never edit an
applied migration, keep its tests green.

**Consequences.** Mature, tested account security from day one. Identity uses
JPA and its own conventions, unlike the other services. Upstream fixes arrive by
cherry-pick.

**Rejected.** Keycloak or another identity provider — contrary to the brief.
Depending on Bastion as a library — it is a template, not a library.

## ADR-009: Asynchronous order placement

**Context.** Checkout should be fast and should work while the payment provider
is slow.

**Decision.** `POST /orders` validates, prices, stores the order as `PENDING`
with its outbox event, and returns `202` with the order id. The storefront shows
the order page and polls its status (every second until it settles, then stops).
An `Idempotency-Key` header makes retries safe.

**Consequences.** Checkout latency is one local transaction. The customer sees
"confirming…" for a second or two, and can be told later that payment failed.

**Rejected.** Holding the request open until payment completes — couples
checkout latency and availability to three services and a third party.
WebSockets or SSE for status — polling one indexed row for a few seconds is
simpler and survives replicas without sticky sessions.

## ADR-010: Data other services need arrives as events

**Context.** The order service needs prices; the catalog needs availability.

**Decision.** Event-carried state transfer. Catalog publishes `product.upserted`;
the order service keeps a `products` snapshot and prices orders from it;
inventory creates stock rows from it. Inventory publishes
`inventory.stock-changed`; catalog keeps an `available` column for display.

**Consequences.** No request-time dependency between services. Copies lag by
milliseconds to seconds: a just-changed price can differ between page and order,
which checkout detects (`409 PRICE_CHANGED`); displayed availability is advisory
and the reservation is the truth.

**Rejected.** Calling catalog at checkout — breaks 002. A shared cache — a
hidden shared database.

## ADR-011: Observability stack

**Context.** "Excellent logging, traceability and monitoring", runnable in
Compose, without vendor lock-in.

**Decision.** Structured JSON logs (ECS) to stdout, collected from Docker by
Grafana Alloy into Loki. Traces by OpenTelemetry (Spring Boot's starter) over
OTLP to Alloy and on to Tempo; W3C trace context carried through HTTP and Kafka,
including across the outbox. Micrometer metrics scraped by Prometheus. Grafana
provisioned with all three, cross-linked. Every log line carries the trace id;
every response the trace and request ids.

**Consequences.** One id leads from a customer's error to every log line and
span behind it. Services know nothing about where logs go. Alloy needs read
access to the Docker socket — it runs on the telemetry network only, with no
published port.

**Rejected.** ELK — heavier to run. Shipping logs over OTLP from the JVM — works,
but loses the logs of anything that is not a JVM (nginx, Kafka, MySQL) and of a
JVM that fails to start. A SaaS (Datadog, New Relic) — not self-contained.
Zipkin/Jaeger — Tempo integrates with the Grafana already chosen.

## ADR-012: Alerting and incident tracking

**Context.** Alerts must reach someone and be tracked to resolution.

**Decision.** Alert rules in Prometheus, versioned in the repository, each with
a severity, a summary and a runbook link. Alertmanager groups and de-duplicates,
then (a) always posts to the ops service, which stores each alert as an incident
with history, acknowledgement and notes, and (b) notifies people through a
per-environment receiver. Symptom alerts (SLO burn rate on availability and
latency, probe failures) page; cause alerts (lag, dead letters, outbox age,
breaker open, saturation) warn.

**Consequences.** There is a record of what fired, when, who acknowledged it and
what was done, inside the system. Real paging integration (PagerDuty, Opsgenie,
Slack) is a receiver configuration per environment.

**Rejected.** Grafana-managed alerts — rules live in a database instead of the
repository. An external incident tool only — nothing to see in a self-contained
deployment.

## ADR-013: A custom ops console

**Context.** The brief asks for a monitoring site showing the state and
statistics of the whole system and each part, and able to start and stop problem
producers and consumers.

**Decision.** A React console backed by an `ops` service that aggregates
Prometheus, Kafka's admin API, Loki and its own incident store, and proxies the
DEV testkit. Grafana stays, for exploration, linked from the console.

**Consequences.** A purpose-built view (topology, order funnel, dead-letter
browser, chaos controls) that Grafana cannot provide, at the cost of code to
maintain. The console holds no state of its own beyond incidents.

**Rejected.** Grafana alone — no actions, no dead-letter browser, no chaos
controls. An off-the-shelf Kafka UI — covers one page of seven, and is another
image to secure.

## ADR-014: Test tooling is DEV-only

**Context.** DEV must have test data, simulated customers, Mailpit and problem
producers/consumers. INT, QA and PROD must have none of it.

**Decision.** All test tooling lives in three containers — `testkit`,
`simulators`, `mailpit` — defined only in `deploy/compose.dev.yml`. Deployed
service images contain no test data or test code paths: data is seeded by the
testkit through the services' normal APIs. Enforcement, three ways: (1) the
containers do not exist in other environments' Compose models, which CI asserts
(`scripts/verify-clean-deployments.sh`); (2) `TestToolingGuard` in every service
refuses to start outside dev if a setting points at a test facility; (3) the ops
service exposes the testkit proxy and the console renders its pages only when
`commerce.environment=dev` and a testkit URL is configured.

**Consequences.** INT, QA and PROD need real endpoints for the payment provider,
carrier and SMTP (typically the providers' sandboxes in INT and QA); Compose
refuses to start without them. INT and QA start with an empty catalog, filled
through the back office (022).

**Rejected.** Spring profiles that switch test features on inside the services —
test code ships to production and one environment variable enables it.
Simulators in INT/QA — convenient, but the instruction is that only DEV has test
tooling; revisit if INT/QA must run without provider sandboxes
([design review](design-review.md#open-decisions)).

## ADR-015: External dependencies

**Context.** Payment providers and carriers are slow, fail, and time out
ambiguously.

**Decision.** Every external call has connect and read timeouts, one retry on
connection failure or 5xx, a circuit breaker, and an idempotency key (the order
id) so a repeat cannot double-charge or double-book. No external call happens
inside a database transaction. Billing calls the provider before its transaction
(the customer is waiting); shipping and notification record work and let a worker
call the carrier or SMTP server. Requests carry deadlines. An unavailable payment
provider fails the payment promptly rather than leaving the order to time out.

**Consequences.** A third party's outage costs the feature that needs it and
nothing else; database connections are never held hostage by a slow partner.

**Rejected.** Calling inside the handler's transaction — simplest, but a slow
provider exhausts the connection pool. Queueing payments like shipments — adds
seconds to every checkout.

## ADR-016: Hardened containers and segmented networks

**Decision.** Bastion's container rules for every container: non-root,
read-only filesystem, no capabilities, no privilege escalation, memory and PID
limits, images pinned by digest. Compose networks are internal and
purpose-specific: edge, data, events, telemetry, plus dev's sandbox, mail and
sim; only named services can reach the internet; only edges and loopback-bound
tool UIs publish ports.

**Consequences.** A compromised container reaches only what its role needs.
More Compose to write, and two documented exceptions: Alloy reads the Docker
socket, and Kafka/MySQL need writable volumes.

## ADR-017: One build, one Dockerfile

**Decision.** A Gradle multi-project build (`platform:core`,
`platform:messaging`, `services:*`). One multi-stage `services/Dockerfile`
builds every jar once (BuildKit cache mount for Gradle) and produces one image
per service selected by a build argument, with Spring Boot layered jars. Each
SPA has its own Dockerfile producing an nginx image.

**Consequences.** Consistent versions and one cache; a change to the platform
library rebuilds every service image, which is correct. Services remain
separately deployable artifacts.

**Rejected.** A repository per service — independent release cadence nobody
needs yet, at the cost of versioning the shared contracts. Buildpacks — less
control over the hardened runtime image.

## ADR-018: CI/CD

**Decision.** GitHub Actions. CI on every pull request and push: build, tests
(unit, contract fixtures, Testcontainers integration), static analysis, SPA
builds, clean-deployment verification, image builds, and an end-to-end smoke test
against the running DEV stack. Security workflow: secrets scan, image and
dependency scans, hardening verification. CD: build images once per commit, tag
by SHA, push to GHCR, and promote the same digests dev → int → qa → prod through
GitHub Environments with their own secrets and approvals; prod only from a
version tag. Actions are pinned by commit SHA.

**Consequences.** What runs in prod is byte-for-byte what was tested. Deploys
are Compose over SSH and are not rolling (see architecture §15).

## ADR-019: Technology versions

Verified against this machine and the registries on 2026-11-16.

| Technology | Version | Note |
|---|---|---|
| Java | 25 (LTS) | Temurin; images pinned as in Bastion |
| Spring Boot | 4.1.2 | Same as Bastion; Jackson 3 (`tools.jackson`), modular starters |
| Gradle | 9.8.2 (wrapper) | From Bastion |
| Spring Kafka / kafka-clients | 4.1.2 / 4.2.2 | Managed by Boot |
| Kafka broker | `apache/kafka:4.4.0` | KRaft, no ZooKeeper |
| MySQL | 9.7 (LTS) | Bastion's pinned image |
| Node / React / Vite / TypeScript | 26 / 19 / 8 / 7 | As Bastion's frontend |
| nginx | `nginxinc/nginx-unprivileged:1.30` | Bastion's pinned image; `resolve` in upstreams needs ≥ 1.27.3 |
| Prometheus / Alertmanager | v3.16.0 / v0.35.0 | |
| Grafana / Loki / Tempo / Alloy | 13.3.1 / 3.7.10 / 2.10.10 / v1.22.0 | Tempo 2.x deliberately (3.x is a different architecture) |
| Resilience4j | 2.x core modules | Used programmatically, no Spring Boot starter |
| Caffeine | Boot-managed | Catalog cache |

## ADR-020: Plain SQL for domain services

**Decision.** Domain services use Spring's `JdbcClient` with hand-written SQL
and Flyway migrations. Identity keeps JPA as Bastion wrote it.

**Consequences.** The conditional updates, `INSERT IGNORE`, row locks and
`SKIP LOCKED` the design depends on are written exactly as intended and are
visible in review. More mapping code than JPA.

**Rejected.** JPA everywhere — its unit-of-work hides the statement-level
atomicity these services rely on.

## ADR-021: Simulated shoppers are real customers

**Context.** Goal 2 is to watch the real system respond to traffic. Traffic that
bypasses the edge and identity would leave half the system unobserved.

**Decision.** The testkit creates its shoppers through the real signup and email
verification flow, signs them in, and drives every request through
`storefront-web` with real tokens. Each shopper sends its own `X-Forwarded-For`
address; in dev only, the storefront edge trusts that header from the testkit
(Bastion's `trusted-proxies.conf`, mounted by `compose.dev.yml`), so per-IP
limits apply per shopper. No rate limit or security control is disabled.

**Consequences.** Simulated load exercises the edge, the auth check, identity,
and every service. Shopper creation is paced by signup cost (Argon2) — tens of
shoppers take seconds, which is fine at startup.

**Rejected.** Calling services directly with forged edge headers — simpler, but
unrealistic and it would need the edge secret outside the edge. Disabling rate
limits in dev — hides behaviour worth seeing.

## ADR-022: Back office in the storefront

**Context.** With test data confined to DEV, the other environments need a way
to create products and stock; support needs to look up any order.

**Decision.** Administrators get back-office pages in the storefront app, next
to Bastion's user administration: products and prices (catalog), stock levels
(inventory), and order lookup with full history (order, read-only). APIs live
under `/api/v1/backoffice/**`, require an administrator with a two-factor
session at the edge and in the service, and write an audit log line per change.

**Rejected.** Putting it in the ops console — that console observes; mixing in
business mutations blurs who may do what.

## ADR-023: Live feed over Server-Sent Events

**Context.** Prometheus-backed panels trail by a scrape interval; watching cause
and effect needs sub-second feedback.

**Decision.** The ops service consumes every topic in its own group
(`ops-live`, starting from latest, never committing work), keeps rolling counters
and the last few hundred event headers in memory, and streams them to the
console over SSE. Payloads are not exposed.

**Consequences.** Real-time visibility in every environment with no extra
infrastructure. Each ops replica holds its own window; the console talks to one.

**Rejected.** WebSockets — bidirectionality not needed. Faster Prometheus
scraping — costly and still not real time.

## ADR-024: Kafka authentication and ACLs

**Context.** With network isolation alone, any container on the events network
could write to any topic or join any consumer group. Consumers defend themselves
against bad data, but nothing stopped a client that should not be there, and
nothing enforced "only the owner writes to its topic".

**Decision.** Every environment runs Kafka with authentication and
authorisation. Clients authenticate with SASL/SCRAM-SHA-512, one principal per
service. The KRaft `StandardAuthorizer` denies by default
(`allow.everyone.if.no.acl.found=false`). A one-shot `kafka-init` job creates the
SCRAM credentials, the topics and the ACLs in the table in architecture §5: a
service may write only its own topics, read only what it consumes with its own
group, and write to the dead-letter topics of what it consumes. `ops` may read
everything and write only to replay a dead letter. DEV adds three principals for
the testkit: a reader, a deliberately over-privileged `testkit-chaos` writer, and
an `intruder` with no rights. Listeners are `SASL_PLAINTEXT` on the internal
network; inter-broker and controller listeners use SASL/PLAIN with a broker
credential.

**Consequences.** A buggy or compromised service cannot forge another's events
or stall another's consumer group; the broker itself refuses an intruder, which
the chaos lab demonstrates. Services no longer create topics. Cost: a credential
per service (`KAFKA_PASSWORD_<SERVICE>`), JAAS configuration on every client,
and an init job that must finish before services start. Connections are not
encrypted inside the Docker network — a multi-host deployment switches the
listeners to `SASL_SSL`.

**Rejected.** Network isolation only — the first draft; does not stop the
players it is meant to. mTLS client certificates — equally strong, but a
certificate authority and per-service certificates are more to operate in
Compose than passwords. OAuth bearer tokens — needs a token service.

## ADR-025: Personal data across services

**Status: Open.** Events and service databases hold emails and addresses.
Bastion's account deletion and data export cover only identity. Recommendation:
identity publishes `user.deleted` (and the other services anonymise the personal
fields they hold, keeping the order records), and export gathers each service's
data for the user. This touches Bastion's `AccountService` (additively). See the
design review.

## ADR-026: DEV runs the HA topology by default

**Context.** The purpose of DEV is to watch the system respond and to prove it
is resilient. That needs something to fail over to.

**Decision.** The high-availability topology is one overlay,
`deploy/compose.ha.yml`: two replicas of every stateless service and three Kafka
brokers with replication factor 3 and `min.insync.replicas=2`. DEV, QA and PROD
all include it; INT runs single instances. `scripts/stack.sh dev --lite` runs DEV
without it for machines with less memory. On one Docker host this is
availability against process failure, not host failure; MySQL and the edges are
single containers.

**Consequences.** DEV was expected to need about 12 GB of memory for Docker;
measured at milestone 9, the HA topology uses about 6.4 GiB and runs on 8 GB
(10 GB recommended for headroom under simulated load). The topology that is proven in DEV is the one QA and PROD run.

**Rejected.** HA opt-in for DEV (the first draft) — the default environment
could then prove nothing about failover. Docker Swarm locally — rolling updates,
but still one host and not the Compose the brief asked for.

## ADR-027: Instance control from the console

**Context.** The owner wants replicas and brokers visible in the monitoring
site with controls to start and stop them. That requires something inside the
deployment to command Docker — a privilege that amounts to control of the host
if granted carelessly. The first draft avoided it with a host-side script.

**Decision.** In DEV only, two extra containers. `docker-proxy` is the sole
holder of the Docker socket and exposes only the container list and the start,
stop, kill, pause, unpause and restart calls. `controller` is the only client
of the proxy; it acts only on containers of this Compose project whose service
is on an allowlist, never on itself, the proxy, the ops service, the ops edge or
the observability stack, and never creates containers. The ops service forwards
console requests to it only when the environment is dev. Every action is
recorded. Outside dev none of this exists and the Instances page is read-only,
fed by Prometheus and Kafka.

**As built (milestone 9a).** The proxy is nginx with an explicit method-and-path
allowlist rather than `tecnativa/docker-socket-proxy`, whose rules cannot allow
the lifecycle actions without also allowing container creation and exec (its
`POST=1` switch applies to every allowed section). `scripts/verify-instances.py`
checks that create, exec, images, volumes and updates are refused.

**Consequences.** Failures can be injected and undone from the same screen that
shows their effect. The Docker socket is present in DEV behind two layers of
restriction; it is absent from INT, QA and PROD, which CI verifies.

**Rejected.** Mounting the socket into the ops service or testkit — too much
power in containers with wide network reach. A host script only — safe, but not
what was asked and not observable from the console. Scaling by creating
containers — needs far broader Docker rights than stopping and starting a fixed
pool.

## ADR-028: Resilience is proven by experiments with verdicts

**Context.** "We are resilient" must be demonstrable, repeatably, to someone who
did not build the system.

**Decision.** Chaos-engineering experiments as a feature of DEV: each has a
steady-state hypothesis, an injected failure, an observation window and an
automatic verdict from customer impact, order completion, and a ledger audit
that reconciles the shoppers' actions with every service's records (orders,
payments, refunds, stock, shipments, emails). Experiments are run from the
console or `scripts/prove.sh`; results are stored and exportable; CI runs a
short subset.

**Consequences.** Resilience claims become regression tests. The audit needs
read-only back-office endpoints in billing, inventory, shipping and
notification, and the testkit must record everything it does.

**Rejected.** Dashboards only — evidence, not proof. An external chaos tool
(Chaos Mesh, Gremlin, Pumba) — built for Kubernetes or lacking the
business-level audit that makes the verdict meaningful.

## ADR-029: Bounded password hashing in identity

**Context.** Found at milestone 7: twenty simulated shoppers signing up at once
crashed identity with `OutOfMemoryError`. Each Argon2id hash or check takes
about 19 MiB of heap, and nothing limited how many ran together — so anyone
able to send concurrent sign-in or sign-up requests could take identity, and with
it every authenticated request in the shop, down.

**Decision.** Identity's `PasswordEncoder` is wrapped in `BoundedPasswordEncoder`:
at most `MAX_CONCURRENT_HASHES` (default 4) hashes run at once; further requests
wait up to 10 s for a slot and then get `503 BUSY`. Argon2's parameters, and so
the cost of each guess for an attacker, are unchanged. Identity's container
limit is raised from 448 to 640 MiB, which the same load showed it needs.

**Consequences.** Memory for hashing is bounded at about 76 MiB. A burst of
sign-ins queues instead of crashing the service; past ten seconds it is told to
retry. A queued request holds its database connection while it waits (Bastion
checks the password inside its transaction), so the bound is kept small
relative to the pool (20). This is an additive change to Bastion: no control is
weakened, and its test suite passes unchanged (plus two tests for the bound).

**Rejected.** Cheaper Argon2 parameters — weakens stored passwords. Relying on
the edge's per-IP limits — a distributed burst passes them.

## ADR-030: Infrastructure failures are retried, not dead-lettered

**Context.** Found while building the database-outage experiment (milestone 9b):
under ADR-005 a handler failure is retried three times over about four seconds
and then dead-lettered. When a service's database is away for twenty seconds,
every event it receives meanwhile fails that way — so a short outage would park
real orders' events on dead-letter topics, and their sagas would stall until an
operator replayed them.

**Decision.** `KafkaErrorHandling` tells the service's own infrastructure apart
from the event: when the failure is a Spring `TransientDataAccessException`,
`DataAccessResourceFailureException` or `CannotCreateTransactionException`, or a
JDBC transient or recoverable exception, the record is retried from 1 s,
doubling to 30 s, with no limit, and the partition waits. Everything else keeps
ADR-005's three retries and dead letter. HTTP requests that hit the same
failures get `503` with `Retry-After` from the messaging module's
`DatabaseUnavailableHandler`, instead of a `500`.

**Consequences.** A database outage delays events; it does not lose or park
them, and the services catch up without help. A consumer whose database stays
away holds its partitions and its lag grows — which is what alerts on. A bug
that happens to throw one of these exceptions would be retried for ever rather
than dead-lettered; the lag alert surfaces that too.

**Rejected.** More retries for everything — a poison event would block its
partition for as long. Pausing the listener container on a failed health check
— more moving parts for the same result.

## ADR-031: Refunds are queued and retried until made

**Context.** Found by the resilience experiments (milestone 9b). Billing called
the provider's refund inside the event's handling (before the transaction, as
ADR-015 requires), so a refund the provider could not take was retried three
times in about four seconds and dead-lettered. When the payment simulator was
unreachable for a moment, three returned orders were left `RETURNED` with their
payments still `AUTHORIZED`: the customers would not have been refunded until
an operator noticed and replayed the dead letters. Retrying in the consumer
until the provider is back (as ADR-030 does for the database) would instead
block every charge behind the refund on that partition for the whole outage.

**Decision.** `order.cancelled` and `order.returned` only queue the refund: in
the event's transaction the payment moves `AUTHORIZED` → `REFUND_PENDING`.
`RefundWorker` leases due refunds (`FOR UPDATE SKIP LOCKED`, as shipping and
notification do), calls the provider outside any transaction with the same
idempotency key (`refund-<orderId>`), and in one transaction marks the payment
`REFUNDED` and emits `payment.refunded`. A failure is retried 5 s, doubling to
5 min, with no limit; attempts and the last error are kept on the payment and
shown in the back office. Migration `V2__refund_queue.sql`.

**Consequences.** A provider outage delays refunds and never loses them;
charges carry on (or fail fast as `PAYMENT_UNAVAILABLE`) meanwhile. A refund
the provider refuses for good (it no longer knows the charge, say) is retried
every 5 min and raises `RefundsStuck` after 15 min for an operator, instead of
sitting on a dead-letter topic. The ledger audit counts a refund still queued
after the system settles as a failure.

## ADR-032: A public repository

**Context.** CI must start the DEV HA stack (about 6.8 GiB at rest) and run the
resilience subset on every change, and the design's CodeQL and dependency
review workflows (ADR-018) need GitHub's code scanning. On a private repository
the standard runner has about 7 GB, too little for the HA stack beside the
build; CodeQL and dependency review need GitHub Advanced Security, which the
repository did not have; and every minute is billed.

**Decision.** The owner made the repository public (2026-11-17), as Bastion's
already is. Before the change the whole history was scanned for secrets
(gitleaks: one finding, a test fixture's made-up edge secret, now allow-listed
exactly).

**Consequences.** Standard runners (4 CPUs, 16 GB) run the HA stack; CodeQL,
dependency review and Dependabot alerts work as designed; Actions minutes are
free. Everything committed, including history and commit author addresses, is
public: secrets stay out of the repository as before (environment files are
git-ignored; gitleaks runs over the full history on every change).

**Rejected.** A `--lite` stack in CI — the HA experiments (replica and broker
crash) could no longer run there. Paid larger or self-hosted runners — cost, or
a host that runs pull-request code.

## ADR-033: DEV simulations are off until switched on

**Context.** DEV has the test tooling (ADR-014): simulators, Mailpit, the
testkit with test data, simulated shoppers and the chaos lab. Until now the
testkit seeded a shopper account and products at startup and kept topping stock
up, so every DEV stack was full of made-up data whether anyone wanted it or not.
A developer working on a feature usually wants an empty system they fill
themselves; the demonstration wants the full simulation.

**Decision** (owner, 2026-11-17). DEV keeps all of its tooling, but every data
simulation is off by default and switched on from the ops console: test data,
simulated shoppers, each chaos actor, and the simulators' faults. Only the DEV
administrator is created at startup, because it is setup, not data: without it
nobody could sign in to switch anything on. Switching off stops a simulation and
keeps what it made. The switches are remembered across restarts; a fresh stack
starts with all off. Experiments and the verification scripts switch on what they
need, and experiments restore the switches afterwards. A separate DEMO environment was
considered and dropped by the owner the same day.

**Consequences.** `stack.sh dev up` gives an empty shop with one administrator.
The testkit gains a small persisted state (its switches); the console's
Simulated shoppers page gains a Test data switch. The ledger audit and the
experiments are unchanged in what they check. Nothing changes outside DEV.

**Rejected.** A DEMO environment holding the tooling while DEV is clean — DEV
would then lose the simulators and Mailpit, or need sandbox endpoints like INT.
Deleting a simulation's data when it is switched off — the services have no
delete APIs for orders or accounts, and data that disappears is not how real
data behaves.


## ADR-034: The load baseline is measured locally; GitHub is optional

**Context.** Milestone 11 measures the latency targets in architecture §8 and
names the bottleneck by stepping the simulated shoppers up to 20 orders/s
(`scripts/load-baseline.py`). It can run on the development machine (Docker
Desktop) or on a GitHub-hosted runner (`load.yml`, 4 CPUs, 16 GB, Linux). The
HA stack uses about 6.8 GiB at rest, so an 8 GB Docker Desktop is too small
under load; the development machine now gives Docker Desktop about 11.7 GiB and
22 CPUs.

**Decision** (owner, 2026-11-18). The baseline of record is measured locally on
the DEV HA stack, with the host it ran on (Docker Desktop memory and CPUs, OS)
written into the report beside the numbers. `load.yml` stays as an optional
extra, run by hand (`workflow_dispatch`) or by labelling a pull request
`load-baseline`, for a Linux measurement to compare against later. Neither
runs on every change.

**Consequences.** The numbers describe one Windows/Docker Desktop host, not a
production host; the report says so, and a bottleneck that is Docker Desktop's
(its VM, its networking) is named as such. Docker Desktop needs more than 10 GB
for the run. A GitHub run, when made, is reported beside the local one, not in
place of it.

**Rejected.** GitHub only — the owner wants the run under their own control.
Deleting `load.yml` — the owner wants a Linux run possible later.

## ADR-035: The password-hashing bound is set per environment

**Context.** ADR-029 bounds how many Argon2id hashes identity runs at once
(`MAX_CONCURRENT_HASHES`, Bastion's `security.max-concurrent-hashes`, default
4), but compose never passed the setting, so every environment got 4. The
right value depends on the host. During milestone 11 a DEV replica hit its
640 MiB container limit and was killed. The cause was a burst of sign-ins,
because every simulated shopper's access token expired at the same moment. A
production replica with dedicated cores and memory can afford more.

**What the value is.** The value applies to each identity replica: it is how
many password hashes or checks (sign-up, sign-in, password change and reset)
that replica runs at the same moment. Each takes about 19 MiB of heap and about
50 ms of one core. A request beyond the bound waits for a free slot, up to
10 s, and then gets `503 BUSY` ("try again"). The value sets three things:

- memory for hashing: about value × 19 MiB of heap;
- password checks per second per replica: about value ÷ 0.05 s, as long as
  the replica has that many cores;
- database connections held while waiting: Bastion checks the password inside
  its transaction, so a queued request holds one of the 20 in the pool
  (`DB_POOL_SIZE`).

**Decision** (owner, 2026-11-18). Compose passes `MAX_CONCURRENT_HASHES`
through, and each environment's settings file sets its own value:

| Environment | Value | Why |
|---|---|---|
| DEV | 2 | Every service shares one laptop's Docker VM, and identity has 640 MiB. Two replicas still check about 80 passwords/s, so 200 simulated shoppers signing in at once clear in about 2.5 s, well inside the 10 s wait |
| INT, QA | 4 | Bastion's default, in the same 640 MiB as DEV |
| PROD | 8 | Recommended starting point, with identity's `mem_limit` raised to 768 MiB in `compose.prod.yml` |

**Recommended for production.** Size the value to the identity replica, not
to expected traffic:

1. Use about one per core the replica can count on. A hash is CPU-bound, so a
   value above that only makes each hash slower, not more of them.
2. Keep it at or below half of `DB_POOL_SIZE`, so that sign-ins waiting for a
   slot never starve the rest of identity of connections.
3. Make sure it fits the container. The heap is 60% of `mem_limit`
   (`-XX:MaxRAMPercentage=60`), and it needs about 200 MiB for the application
   plus value × 19 MiB. The other 40% has to hold about 165 MiB of non-heap
   memory, thread stacks and the 64 MiB `/tmp`. For 8 that is 768 MiB. For 16
   (eight dedicated cores) raise `DB_POOL_SIZE` to at least 32 and `mem_limit`
   to about 1 GiB.
4. Add capacity with replicas (each has its own bound), not by raising the
   value past the cores.
5. Watch for `503 BUSY` on sign-in and for identity's heap. Persistent `BUSY`
   under real traffic means more replicas, not a higher value on the same
   container.

**Consequences.** DEV queues sign-in bursts sooner and uses less heap for
them. That lowers the pressure, but DEV identity's headroom in 640 MiB is
still thin (implementation plan §1). PROD checks twice as many passwords per
replica and has the memory for them. A value of 0 or below fails at startup,
as it already did. Argon2's parameters, and so the cost of each guess for an
attacker, are unchanged in every environment, so this weakens no control.

**Rejected.** One value for every environment. Too high for a laptop, or too
low for a production host. An unbounded or very high value in PROD. That
reopens ADR-029's denial of service. Cheaper Argon2 parameters in DEV. They
would make DEV's passwords unlike production's, and the hashes would differ.

## ADR-036: The latency targets stay; this machine's capacity is recorded beside them

**Context.** Milestone 11 measures the targets in architecture §8 on the
development machine (ADR-034). The first runs show the shared MySQL's commit
path as the bottleneck, not CPU. The configuration is fully durable
(`innodb_flush_log_at_trx_commit=1`, `sync_binlog=1`), and one synchronous
4 KiB write on Docker Desktop's volume takes 12–15 ms, against well under 1 ms
on a Linux NVMe disk. On this machine `POST /orders` p95 is over 250 ms from
3 orders/s, and place → confirmed p95 is over 3 s from about 15/s.

**Decision** (owner, 2026-11-18). The three targets stay as they are and keep
setting the production alert thresholds: catalog reads p95 < 100 ms at the
edge, `POST /orders` p95 < 250 ms, place → confirmed p95 < 3 s. Architecture §8
records, beside them, this machine's measured capacity: for each target, the
highest rate at which the baseline of record met it, the host it ran on, and
the bottleneck that ended it. The bottleneck is said to be Docker Desktop's
when it is.

**Consequences.** On this machine the targets are not met at every rate, and
§8 says so rather than moving the thresholds to fit. The capacity figures
describe one Windows/Docker Desktop host. A Linux run (`load.yml`), when made,
is recorded beside them, not in place of them. Production alerts keep their
meaning.

**Rejected.** Replacing the targets with the measured numbers. That would
build Docker Desktop's fsync time into production's alert thresholds.


## ADR-037: Every JVM service has a measured memory budget

**Context.** Every JVM service gave its heap 60% of the container's
`mem_limit` (`-XX:MaxRAMPercentage=60`) and left the other 40% for everything
else. The milestone 11 run at 17:37 UTC on 2026-11-18 showed that the 40% is
not enough under load. The kernel of Docker Desktop's VM killed identity six
times (both replicas at once at 17:51, during the 15/s step, where 26.9% of
checkouts failed), order and shipping four times, Alloy once and MySQL once,
and Tempo over a hundred times. Docker did not report the kills, and the
services logged nothing first. ADR-035's lower hashing bound did not stop
identity's. Peaks measured during that run (Prometheus):

| Service | Limit | Heap committed | Non-heap committed | Native (RSS − JVM) |
|---|---|---|---|---|
| identity | 640 MiB | 371 MiB | 186 MiB (metaspace 139) | ~100 MiB |
| order | 320 MiB | 185 MiB | 149 MiB | ~78 MiB |
| shipping | 320 MiB | 146 MiB | 153 MiB | — |
| billing, catalog, inventory, notification, ops | 320 MiB | 75–96 MiB | 135–152 MiB | — |
| simulators | 256 MiB | 149 MiB | 91 MiB | — |

Non-heap memory is mostly class metadata (90–140 MiB, fixed once the
application has started) and compiled code, which grows under load (40–55 MiB).
Together with native memory (thread stacks, GC and JIT structures) it needs
about 230 MiB in a 320 MiB container, so a 60% heap cannot fit. Every service
stood at its limit even after the load stopped. The heap does not shrink, so
the next burst kills it. Separately, all the limits together (10.9 GiB) came
within 0.8 GiB of the Docker VM's 11.7 GiB.

**Decision** (owner, 2026-11-18).

1. Each JVM service's heap is set explicitly (`JVM_HEAP`, compose), as its
   `mem_limit` minus its measured non-heap peak, minus about 80 MiB of native
   memory (100 MiB for identity), rounded down. The image refuses to start
   without it. Compiled code is capped at 64 MiB
   (`-XX:ReservedCodeCacheSize=64m`; peak use was 54 MiB), so non-heap memory
   has a known ceiling. The rest of the flags are unchanged (`SerialGC`,
   `ExitOnOutOfMemoryError`).
2. Limits are raised where the budget does not fit, in every environment,
   because the need comes from the application and not from the host:

   | Service | `mem_limit` | `JVM_HEAP` |
   |---|---|---|
   | identity | 768 MiB (was 640; PROD was already 768) | 464 MiB |
   | order, shipping | 448 MiB (was 320) | 208 MiB |
   | billing, catalog, inventory, notification, ops | 384 MiB (was 320) | 144 MiB |
   | testkit (DEV) | 384 MiB | 176 MiB |
   | simulators (DEV) | 320 MiB (was 256) | 144 MiB |
   | controller (DEV) | 320 MiB | 152 MiB |
   | Tempo | 512 MiB (was 320) | — |

3. The DEV HA stack's limits now add up to about 12.5 GiB. The Docker VM
   must have more than that for a load run. `load-baseline.py` refuses to
   start when the running containers' limits exceed the VM's memory
   (`--overcommit` runs anyway and says so in the report). It also marks a
   step during which any container was killed or restarted as not a
   measurement. It does not run such a step again, because the load caused
   the kill.

**Consequences.** A service killed for memory is now a sizing error to fix
here, not noise in a run. Heap and limit are two numbers to change together.
Compose keeps them side by side, and this record holds the measurements they
came from. On the development machine, Docker Desktop needs about 14 GiB
(`.wslconfig` `memory=`) for the load baseline; the README asks for 14 GB.
`glibc`'s `MALLOC_ARENA_MAX` does not apply: the image is Alpine (musl).
ADR-035's sizing rule ("the heap is 60% of `mem_limit`") is replaced by
item 1. Its hashing values are unchanged, and PROD's 8 fit identity's 464 MiB
heap (about 200 MiB + 8 × 19 MiB).

**Rejected.** Only raising the limits and keeping 60%: the heap grows with the
limit and the non-heap share stays short. A lower percentage for everyone: the
non-heap need is nearly constant (about 230 MiB) while limits differ, so no
one percentage fits both a 320 MiB and a 768 MiB container. Capping metaspace:
it is the application's own classes, so a cap would only turn a kill into an
`OutOfMemoryError` at the same moment. Cutting DEV's trace sampling to save
Tempo's memory: DEV keeps full traces for the experiments' evidence.
