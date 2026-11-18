# Implementation plan

The build specification for the design in [architecture.md](architecture.md) and
[decisions.md](decisions.md). Read those first; this document says what to
create, in what order, and how to know each step is done. Where this document
and the architecture disagree, the architecture wins — fix this document.

- [1. Current state](#1-current-state)
- [2. Repository layout](#2-repository-layout)
- [3. Conventions](#3-conventions)
- [4. Verified facts and gotchas](#4-verified-facts-and-gotchas)
- [5. Module specifications](#5-module-specifications)
- [6. Build order and acceptance checks](#6-build-order-and-acceptance-checks)
- [7. Definition of done](#7-definition-of-done)

## 1. Current state

As of 2026-11-17: **milestones 0–10 are complete**; milestone 10a (DEV
simulations off by default, ADR-033) is next, then 11.

Milestone 10 acceptance (PR #12, a clean Linux clone on GitHub's runners): CI
green — `./gradlew build` with no test skipped, both SPAs, clean deployments,
dependency review, and the DEV HA stack with `smoke.sh` (61 checks) and
`prove.sh --quick` (4 of 4 passed, clean audits); Security green — gitleaks,
Trivy on all 13 images and both lockfiles, `verify-stack.sh` (the "cannot reach
the internet" checks included, which pass on Linux); CodeQL green. The README
quickstart is exactly the commands CI runs. CD's deploy jobs have not run: no
environment is listed in `CD_ENVIRONMENTS` yet.

| Area | State |
|---|---|
| `docs/`, `README.md`, `AGENTS.md` | Design written (this set of documents) |
| Root Gradle build (`settings.gradle`, `build.gradle`, wrapper 9.8.2) | Working: `platform:core`, `platform:messaging` and `services:identity` compile |
| `services/identity` | Bastion 2.1.0 renamed (package `com.thirdstone.commerce.identity`, application name `identity`). Added (5.3): `gateway.GatewayAuthController` (`GET /internal/authz[?require=admin]`, refusal reason in `X-Auth-Error`), `ops-requires-mfa` setting, tracing and environment tag in `application.yml`. `./gradlew :services:identity:build`: 93 tests, none skipped. Test-only edits: `MySqlMigrationTest` reads `deploy/mysql/roles.sql` and expects the `<database>_app` login; `application-test.yml` turns trace export off |
| `web/storefront` | Bastion's account pages plus the shop (5.12): catalog with search and categories (typographic product tiles coloured by category; no product images yet), product, cart (localStorage, guarded), checkout (Idempotency-Key per attempt, handles 409 PRICE_CHANGED, test payment tokens only, see architecture §15), orders, order detail (live progress, cancel, return). Back office: products, stock, order lookup, plus Bastion's users. Errors show the request id; sign-in returns to the page that needed it. nginx edge per 5.12; edge-made errors carry `X-Request-Id` |
| `platform/core` | Complete (5.1), 25 tests. Also `web.PageRequest`/`PageResponse`, `data.Ids` (ULID-style ids), `data.Sql` (UTC `DATETIME` ↔ `Instant`) |
| `platform/messaging` | Complete (5.2), 14 integration tests. Infrastructure failures (no database connection or transaction) are retried until they clear, never dead-lettered, and requests get `503` + `Retry-After` (ADR-030). **Test fixtures** (`testFixtures`, used by every event-driven service): `CommerceContainers` (MySQL + Kafka with all topics, shared per JVM), `Edge` (requests as the edge forwards them), `EventBus` (publish events, await what a service announces) |
| `services/catalog` | Complete (5.4), 7 integration tests |
| `services/inventory` | Complete for 5.5 (incl. `order.returned` restock), 6 integration tests |
| `services/order` | 5.6: API (place, list, get, cancel, return), back office, saga incl. shipping and refund events, saga timeout, load shedding, metrics. 11 integration tests. The return and shipping paths are coded but first exercised end to end in milestone 4. Extra column `return_reason` |
| `services/billing` | Complete for 5.7, 9 integration tests against a fake provider (charge, decline, unavailable after one retry, expired, duplicate, cancel before/after charge). 9b: refunds are queued (`REFUND_PENDING`) and made by `RefundWorker`, retried until the provider takes them (ADR-031, `V2__refund_queue.sql`) |
| `services/simulators` | Payment and carrier simulators with fault switches (architecture §11), refuses to start outside dev, 6 tests |
| `services/shipping` | Complete for 5.8: handlers record shipments; `ShipmentWorker` leases due rows (SKIP LOCKED), calls the carrier outside any transaction, announces each step in order, backs off up to 5 min. Carrier breaker. Back office read-only (no addresses). 5 integration tests |
| `services/notification` | Complete for 5.9: six templates, one email per event id, `NotificationWorker` with lease + backoff (1 min doubling to 1 h, `FAILED` after 30 attempts). Back office without bodies. 4 integration tests (mail sender mocked) |
| `services/ops` | 5.10. Experiments (9b, DEV): `/api/v1/ops/experiments` (catalogue, runs, one run and its Markdown report, queue one or several) proxied to the controller, administrators only, names checked against `[a-z0-9-]{1,64}`. Instances (all environments: Prometheus targets, Kafka brokers and group members, outbox relay leaders; in DEV merged with the controller's containers and with controls that are recorded as incidents) and the testkit proxy (7; answers 404 for now): config, overview, service detail and series, recent errors (Loki), business, Kafka (brokers, topics, groups, lag), dead letters (browse, replay with an audit record), incidents (Alertmanager webhook, ack, notes, 90-day purge), live feed (SSE), edge probes and `commerce_kafka_*` gauges. 6 integration tests |
| `web/ops-console` | Console SPA (React 19, Instrument Sans self-hosted, inline-SVG charts): Overview (verdict, system map with live pulses, live feed, totals, funnel, service table), Service detail, Kafka (with the dead-letter browser and replay), Business, Incidents, Instances, Resilience proofs (DEV: catalogue with last verdicts, run one or all, each run's evidence and timeline, download). Sign-in code copied from the storefront. nginx edge `ops-web` on `127.0.0.1:8082`. Not yet looked at in a browser: the Chrome extension was not connected |
| `services/testkit` | DEV only, refuses other environments. Seeder (admin and shopper@example.com through the real sign-up flow via Mailpit, 24 products including an always-sold-out one, stock top-up every 30 s), simulated shoppers (each its own account and `X-Forwarded-For`, believed by the DEV edge from the testkit's fixed address only), journal, contract tap (`commerce_contract_violations_total{producer=real|chaos}`), chaos lab (7 bad producers, the intruder, 4 bad consumers; at most 10 actors, 2 000 records/s). API through the ops proxy. 4 unit tests. 9b: a shopper's checkout that gets no answer, 502, 503 or 504 is retried once with the same Idempotency-Key; the contract tap keeps the last 500 real records per topic, which the `DUPLICATE` producer replays byte for byte |
| `services/controller` | DEV only (refuses other environments). Instances API over `docker-proxy`; an id is always resolved from a fresh list of this Compose project's containers; allowlist plus a denylist of itself, the proxy, ops, ops-web, the observability stack and the init jobs. 3 unit tests. `docker-proxy`: nginx allowlist (`deploy/dev/docker-proxy.conf`), not tecnativa (see ADR-027, as built). **Experiments (9b)**: `controller.experiments` — `ExperimentRunner` (one at a time: steady state, inject, observe, restore always, settle, verdict), the 23-experiment `Catalogue`, `LedgerAudit` over the back-office read APIs (16 unit tests, one per invariant, each shown to fail on a broken ledger), a recovery ledger and the runs on the `controller-state` volume |
| `deploy/` | `compose.yml` (everything that runs everywhere: data, Kafka, the eight services, both edges, observability), `compose.ha.yml` (two instances of each service, three brokers; DEV's default, QA, PROD), `compose.dev.yml` (mailpit, simulators, testkit, dev Kafka principals, dev tool ports, dev trusted proxy), `compose.int.yml`, `compose.qa.yml`, `compose.prod.yml` (outbound network for billing, shipping, notification; PROD: 10% sampling, MFA required), `env/<env>.env.example` for all four, `mysql/`, `kafka/`, `observability/`, `dev/` |
| `scripts/` | (10) `verify-stack.sh` (Bastion's hardening checks for every container: edge headers and refusals, reachability, database logins over TLS with row rights only, runtime restrictions, Docker-socket holders), `deploy.sh` (pull, `up --wait`, clean smoke outside DEV, roll back to the last healthy tag), `smoke-clean.sh` (10 read-only checks for INT/QA/PROD). `prove.sh` (runs the catalogue, `--quick` or named experiments through the console API; Markdown report per run in `build/proofs/`; non-zero unless every one passed). `verify-instances.py` (17 checks: all replicas and brokers listed; stop/start, kill, pause/resume, restart of replicas and a broker shown within seconds; controller refuses ops, observability, unknown ids and actions; proxy refuses create, exec, images, volumes, update; all actions recorded). `verify-clean-deployments.sh` (INT, QA, PROD: refuse to render without settings; no DEV-only service, network, setting or port; COMMERCE_ENV right everywhere; Docker socket in Alloy only — and it fails for DEV, as it should), `verify-ha.py` (two healthy instances of each service, three brokers, every topic RF 3 / min ISR 2, smoke on HA). `stack.sh` pins `COMMERCE_ENV` to the environment asked for and sets the HA Kafka settings. `verify-storefront.py` (9 checks: every page served under the CSP, assets immutable, request id on errors, price-changed shape, a product created from nothing can be bought). `verify-testkit.py` (fresh stack is a shop; 20 shoppers sustain orders, cancellations, returns; ten actors at once with each expected response while `smoke.sh` passes; flood lag recovers; stuck consumer evicted; no real contract violations). `verify-ops.py` (17 checks: console access, dead letters browsed and replayed, live feed, stopped service shown down within seconds, alert → incident → acknowledged → resolved). `verify-observability.py` (9 checks: every service scraped, one trace from `POST /orders` to the confirmation email, its logs in Loki linked to it, `ServiceDown` within 2 min). `stack.sh` (generates `deploy/env/dev.env` on first use and appends settings added to the example later), `smoke.sh` (61 checks on a fresh stack: edge routing, sign-up, forged identity, back office, the order saga's happy path and every failure branch with stock restored, customer cancel with refund, delivery, return to REFUNDED with restock, all six emails in Mailpit, Kafka access control). The saga *timeout* branch is proved by `SagaTimeoutIntegrationTest`, not by smoke.sh (it takes the full 2 min) |
| `.github/` | (10) `ci.yml` (versions agree; `./gradlew build` with no test skipped; both SPAs audited and built; clean deployments; the DEV HA stack with `smoke.sh` and `prove.sh --quick`; dependency review), `security.yml` (gitleaks over history; Trivy over all 13 images and both lockfiles; `verify-stack.sh`), `codeql.yml`, `dependency-submission.yml`, `cd.yml` (CI, then images to GHCR tagged by commit, then dev → int → qa → prod through `deploy.yml`, each only once listed in `CD_ENVIRONMENTS`), `dependabot.yml`. The repository is public (ADR-032) |
| `docs/` | (10) `api.md` (every endpoint through the two edges), `configuration.md` (every setting, CD's included); `operations.md` refreshed with the experiments' observations (§8); `docs/identity/` notes how Bastion's paths map here, links fixed |
| `services/Dockerfile` | One multi-stage file for every JVM service (`--build-arg SERVICE=…`); compiles once, readiness healthcheck |
| `docs/identity/` | Bastion's docs, renamed; paths in them still assume Bastion's layout |
| Root files | `LICENSE`, `SECURITY.md`, `.gitattributes`, `.gitleaks.toml`, `.trivyignore` from Bastion; `.gitignore`, `.dockerignore`. Git repository initialised on `main` |
| Everything else in section 2 | Done, except milestone 11 (load baseline) |

Images already pulled on the development machine: `apache/kafka:4.4.0`,
`prom/prometheus:v3.16.0`, `prom/alertmanager:v0.35.0`, `grafana/grafana:13.3.1`,
`grafana/alloy:v1.22.0`, `grafana/loki:3.7.10`, `grafana/tempo:2.10.10`.

The original Bastion checkout is at `C:\Workspace\bastion` for reference (its
`docker-compose.yml`, Dockerfiles, CI workflows and `scripts/verify-stack.sh` are
the models to follow). Do not modify it.

## 2. Repository layout

```
commerce/
├── README.md  AGENTS.md  CLAUDE.md  LICENSE  SECURITY.md
├── settings.gradle  build.gradle  gradlew  gradle/
├── platform/
│   ├── core/            com.thirdstone.commerce.platform        (web, events, ops)
│   └── messaging/       com.thirdstone.commerce.platform.messaging
├── services/
│   ├── Dockerfile       one multi-stage file, ARG SERVICE
│   ├── identity/        com.thirdstone.commerce.identity        (Bastion)
│   ├── catalog/  inventory/  order/  billing/  shipping/  notification/  ops/
│   ├── simulators/      DEV only
│   ├── testkit/         DEV only
│   └── controller/      DEV only (instance control, resilience experiments)
├── web/
│   ├── storefront/      React SPA + nginx edge (Dockerfile, nginx/)
│   └── ops-console/     React SPA + nginx edge (Dockerfile, nginx/)
├── deploy/
│   ├── compose.yml  compose.dev.yml  compose.ha.yml
│   ├── compose.int.yml  compose.qa.yml  compose.prod.yml
│   ├── env/             <env>.env.example
│   ├── mysql/           roles.sql, apply-roles.sh (all databases)
│   ├── kafka/           init.sh (credentials, topics, ACLs), acls per principal
│   └── observability/   prometheus/ (prometheus.yml, rules/), alertmanager/,
│                        loki/, tempo/, alloy/, grafana/ (provisioning, dashboards)
├── scripts/             stack.sh, stack.ps1, prove.sh, smoke.sh,
│                        verify-clean-deployments.sh, verify-stack.sh
├── docs/                architecture.md, decisions.md, implementation-plan.md,
│                        operations.md, design-review.md, identity/
└── .github/             workflows/ (ci, security, codeql, cd), dependabot.yml
```

Each service: `src/main/java/com/thirdstone/commerce/<service>/`,
`src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init.sql`,
`src/test/java/...`. Services other than identity have no `build.gradle` of their
own unless they need extra dependencies; the root build configures them.

## 3. Conventions

**Code**
- Java 25, records for data, constructor injection, no Lombok.
- Comments in Bastion's style: say what failure or attack a line prevents and
  why the obvious alternative is wrong. No comments that restate the code.
- Domain services: `JdbcClient` + SQL; no JPA. Identity keeps JPA.
- Controllers are thin; a `…Service` class owns transactions; SQL lives in a
  `…Repository` class.
- Every state change is a conditional `UPDATE … WHERE … AND status IN (…)`;
  check the affected-row count instead of reading first.
- Events are written only through `Outbox.append(...)` inside the transaction
  that changes state. Never call `KafkaTemplate` from business code.
- Event handlers are registered through `EventRouter` (section 5.2), never by
  parsing records in the service.
- External HTTP calls go through a client class with timeouts, breaker and
  metrics; never inside a transaction.
- Money: `long` cents + ISO-4217 `String`. Time: `Instant`, UTC, `DATETIME(6)`.
- Ids: orders, shipments, payments are ULID-style 26-character strings generated
  in the service (sortable, safe in URLs); products are keyed by SKU.

**API**
- Base path `/api/v1/<area>`; JSON; errors are `{message, code?, fields?}`.
- Authenticated endpoints take a `Caller` parameter; data is scoped by
  `caller.userId()`; another user's resource is 404.
- Lists are paged: `?page=0&size=20` (max 50) → `{items, page, size, total}`.
- Every new endpoint is documented in `docs/api.md` (create it; link identity's).

**Database**
- Flyway, one migration set per service. `platform:messaging` ships
  `V0_1__messaging.sql` (outbox, processed events); services start at `V1__`.
- Never edit an applied migration. Schema changes are expand-then-contract:
  add in one release, stop using in the next, drop in a third — replicas of two
  versions run side by side during a deploy.
- `utf8mb4`, `InnoDB`, every foreign key and lookup column indexed.

**Configuration**
- Every service's `application.yml` sets `spring.application.name` to the
  service name. `platform-defaults.yml` and `messaging-defaults.yml` are added
  beneath all other configuration by `PlatformDefaults` — do **not** use
  `spring.config.import` for them: an imported file overrides its importer.
- Settings come from environment variables with safe local defaults; secrets
  have no default. Document every variable in `docs/configuration.md`.
- Custom settings live under `commerce.*`.

**Logging**
- `INFO` one line per business state change, with ids as key-values
  (`log.atInfo().addKeyValue("orderId", id)…`); `WARN` handled anomalies; `ERROR`
  needs a human. Never log tokens, passwords, payment tokens or full addresses.

**Tests**
- Name tests after the property they prove.
- Unit tests for pure logic; Testcontainers (MySQL 9.7, Kafka) for anything
  touching SQL or listeners — never H2 for domain services.
- Each event type has fixtures under
  `platform/core/src/test/resources/contracts/<type>/`; add a fixture when a
  payload gains a field; never delete one.

**Containers and CI**
- Follow Bastion: images pinned by tag and digest, Actions pinned by commit SHA,
  hardened container settings (`x-hardened` anchor), healthchecks on everything.

## 4. Verified facts and gotchas

Checked against the resolved Spring Boot 4.1.2 artifacts and this machine.

**Spring Boot 4.1 property names** (several changed from Boot 3)

| Purpose | Property |
|---|---|
| OTLP trace endpoint | `management.opentelemetry.tracing.export.otlp.endpoint` (full URL incl. `/v1/traces`) |
| Sampling | `management.tracing.sampling.probability` |
| Resource attributes | `management.opentelemetry.resource-attributes.*` |
| Turn off OTLP metrics push | `management.otlp.metrics.export.enabled=false` (the OpenTelemetry starter brings `micrometer-registry-otlp`) |
| Kafka listener tracing | `spring.kafka.listener.observation-enabled=true` |
| Kafka template tracing | `spring.kafka.template.observation-enabled` — leave **off** for the outbox relay, which sets `traceparent` itself |
| JSON logs | `logging.structured.format.console=ecs` |

**Starters** (Boot 4 is modular): `spring-boot-starter-webmvc`,
`-validation`, `-actuator`, `-opentelemetry`, `-jdbc`, `-kafka`, `-flyway`
(+ `org.flywaydb:flyway-mysql`), `-mail`, `-cache`; tests:
`spring-boot-starter-test`, `spring-boot-starter-webmvc-test`,
`spring-boot-testcontainers`, `org.testcontainers:testcontainers-mysql`,
`testcontainers-kafka`, `testcontainers-junit-jupiter`.

**Tracing in tests**: `management.tracing.export.enabled=false` also disables
trace *propagation* (the listener starts a new trace instead of continuing the
producer's). Tests that assert propagation keep export on and silence
`io.opentelemetry.exporter` logging instead.

**Edge secret**: every service needs `commerce.edge.shared-secret` (≥ 32
characters) or `commerce.edge.required=false`; tests set it in their
`application.yml`.

**Jackson 3**: packages are `tools.jackson.*` (annotations stay
`com.fasterxml.jackson.annotation`); inject `tools.jackson.databind.json.JsonMapper`;
`JacksonException` is unchecked; unknown properties are ignored by default;
`java.time` works without a module and serialises as ISO-8601 strings.

**Kafka clients**: use `StringSerializer`/`ByteArraySerializer` and
`StringDeserializer`/`ByteArrayDeserializer`; the platform parses bytes itself so
that a malformed record is an ordinary exception in the listener (no
`ErrorHandlingDeserializer` needed). Spring Boot picks up a `CommonErrorHandler`
bean automatically. `DeadLetterPublishingRecoverer` with a destination resolver
returning partition `-1` lets the producer choose the partition.

**nginx**: `server <name>:8080 resolve;` inside an `upstream` with a `zone` and
an http-level `resolver 127.0.0.11 valid=5s ipv6=off;` re-resolves Docker DNS
(needs nginx ≥ 1.27.3). `add_header` does not inherit into a location that has
its own — include the security-headers snippet per location, as Bastion does.
`auth_request` passes 401/403 through; use `auth_request_set` to copy identity's
`X-Auth-*` response headers into the proxied request.

**Bastion specifics**
- Access tokens: HS256, issuer `commerce`, audience `commerce-api`, claims `sub`
  (user id) and `sid` (session public id). `SessionService.resolve(userId, sid)`
  returns the `AuthPrincipal` (user id, email, role, strong-auth flag).
- `RateLimitFilter` only applies to `/api/**`, so `/internal/authz` is exempt.
- Cookie-authenticated endpoints need the header `X-Commerce-Client`.
- Signup: 5 accepted per hour per IP; address validation rejects unknown TLDs —
  use `@example.com` test addresses and `SIGNUP_CHECK_MX=false` in dev.
- `BOOTSTRAP_ADMIN_EMAIL` promotes the first verified account with that address.
- The admin area needs a two-factor session (`ADMIN_REQUIRES_MFA`, default true).
- `DeploymentSafetyCheck` refuses dev passwords and `http://` when
  `APP_BASE_URL` is not localhost.
- `MySqlMigrationTest` and `StructuredLoggingTest` (own JVM) must keep passing.

**Docker networks** have fixed subnets (`${COMMERCE_NET}.<n>.0/24`;
`stack.sh` sets `COMMERCE_NET` to 10.77 for dev, 10.78 int, 10.79 qa, 10.80
prod). Docker's default address pool was exhausted on this machine by other
projects, and a stack of 15 networks should not depend on it. A new network
takes the next free `<n>`: 1–6 in `compose.yml`, 20–21 in `compose.dev.yml`.

**Git Bash on Windows**: it rewrites arguments that look like paths (`/opt/…`)
when calling Windows programs such as `docker`. `smoke.sh` sets
`MSYS_NO_PATHCONV=1`; `stack.sh` converts its own paths with `cygpath -m`, so
it works either way.

**nginx**: an `upstream` zone and a `proxy_cache_path` `keys_zone` share one
namespace — name cache zones `<name>_cache`.

**Alloy** runs as root with every capability dropped, so it may not write to its
image's data directory (owned by another user): its storage is a volume at a new
path, `/var/lib/alloy-state`, which Docker creates root-owned.

**Prometheus** needs `--enable-feature=expand-external-labels` for
`${COMMERCE_ENV}` in `prometheus.yml`. Services are discovered with
`dns_sd_configs` (Docker DNS); add a new service's name there.

**Python on Windows**: `subprocess.run(['bash', …])` can start WSL's bash, which
cannot reach Docker Desktop. Scripts call `docker` directly instead.

**Scheduled tasks** create no spans (`management.observations.enable.tasks.scheduled=false`
in `platform-defaults.yml`); work that belongs to a trace continues it with
`TraceContinuation`.

**nginx**: a directive such as `proxy_read_timeout` may appear once per block,
so a location with its own timeouts (the console's live stream) includes
`proxy-caller.conf` (who the caller is) instead of `proxy-authed.conf` (which
adds `proxy-base.conf`'s timeouts and retries).

**Python on Windows**, again: data piped to `subprocess` in text mode gets CRLF
line endings; send bytes to a Linux container.

**Workers that call external systems** (shipping's carrier worker) claim up to
100 due rows per pass and make up to 8 calls at once on virtual threads: one
call at a time capped shipping at about 10 carrier calls a second, which twenty
simulated shoppers outran (shipments then waited minutes).

**Proxying a query string**: pass it on as a `java.net.URI`, not as a
`RestClient` URI template, or it is encoded a second time (`%3A` → `%253A`).

**Throughput, measured (lite topology, Docker Desktop)**: an event consumer
handles about 120 events/s (3 listener threads, one transaction each, ~25 ms
per commit). A flood of 45 000 events took about 6 minutes to drain, and orders
caught behind a flood longer than the saga timeout are cancelled (`TIMEOUT`) and
compensated, as designed. Input for milestone 11.

**Line endings**: `.gitattributes` makes every text file LF in the working
tree (`* text=auto eol=lf`). With `core.autocrlf=true` a Windows checkout had
turned `dev.env.example` into CRLF, and `stack.sh` appended blank "settings"
to `dev.env`; `stack.sh` now also strips CRs as it reads.

**Seeing pages without the browser extension**: headless Edge renders a page
to an image (`msedge --headless=new --screenshot=… --window-size=1280,900
URL`); its window is at least 500 px wide.

**nginx regex locations** with `{n,m}` must be quoted (`location ~ "…"`), or
nginx reads the brace as a block.

**Docker API from a container**: the socket is `root:root 660` on Docker
Desktop; `group_add: ["0"]` lets an unprivileged process use it with no
capability (running nginx as root instead fails: `chown`/`initgroups` need
capabilities the hardened container does not have).

**Docker Desktop memory**: the HA DEV stack uses about 6.8 GiB at rest; under
the experiments' load it reached the 8 GB VM limit once and the Docker engine
stopped answering (`docker ps` hung, `docker stats` 500) until Docker Desktop
was restarted. The experiments need headroom above 8 GB, or `--lite`.

**Resilience experiments, as run (milestone 9b)**: about 2–3 min each (deploy
under load about 8); the whole catalogue about 75 min on the HA stack. A crashed
run can leave damage later runs trip over (a dead-lettered refund, leaked
reservations from a scratch build): reset DEV (`stack.sh dev down -v`) before a
clean full run. The payment simulator keeps charges in memory, so a refund for a
charge made before it restarted answers 404 and stays queued (ADR-031).
A run's waits re-check once after their deadline: when the host sleeps (Windows
Modern Standby) the clock jumps, and one run failed on a look taken before the
sleep.

**The proofs can fail (9b acceptance, 2026-11-18)**: with both processed-events
checks removed from `EventDispatcher` in a scratch build, `duplicate-storm`
FAILED (`duplicates were recognised: 0 duplicates skipped`). The ledger audit
still held: the replays reached every handler and were ignored by the domain's
own state checks (inventory 2 698, billing 3 116, shipping 3 112, notification
3 477 ignored in 10 min). Once-only effects are therefore defended twice; the
experiment proves the first line (the processed-events table), the audit the
second.

**Full catalogue, 9b acceptance (2026-11-17, run `2026-11-18T0029`, UTC)**:
`scripts/prove.sh` on the HA DEV stack, 23 of 23 PASSED in 74 min, every
ledger audit clean (45–304 orders each), 0 failed checkouts in every run, 0
handler failures dead-lettered. The host went into Modern Standby during
`service-outage-shipping` (18:55–19:15 local), so that outage lasted 20 min
instead of 60 s; it still passed. Reports in `build/proofs/`.

**CI on a clean Linux clone (milestone 10)**: git keeps the executable bit, and
a file added on Windows has none — `gradlew` had none, so CI could not run it
(`git update-index --chmod=+x`). The container scripts are run through `sh`/`bash`
and need none. On Linux the Docker socket belongs to the `docker` group (a
number that differs per host), not root: `stack.sh` passes its id to
`docker-proxy` as `DOCKER_SOCKET_GID` (0 where the host has no socket file,
i.e. Docker Desktop on Windows). Trivy's first run failed every Kafka-using image
on Kafka's codecs (lz4-java 1.11.2, snappy-java 1.1.10.7): pinned past them in
`build.gradle`'s `dependencyManagement`.

**Spring bean names**: Boot already defines `requestContextFilter`; platform
beans use a `commerce` prefix where a clash is possible.

**This machine**: Windows 11, Docker Desktop with 8 GB and 22 CPUs, JDK 25,
Node 22 locally (images build on Node 26). Shell scripts must be LF
(`.gitattributes`). On Docker Desktop the "cannot reach the internet" checks of
Bastion's `verify-stack.sh` fail by design; they pass on Linux CI.

**Memory budget (limits).** DEV's default is the HA topology: 2 × 7 replicated
JVM services at 320 MiB (identity 640), ops 320, testkit 384, controller 256,
simulators 256, 3 Kafka brokers at 512, MySQL 640, Prometheus/Loki/Tempo/
Grafana/Alloy 256 each, nginx and small helpers ~250 — about 10.5 GiB of
*limits*. **Measured (milestone 9)**: the HA topology, all 33 containers, uses
about 6.4 GiB after a smoke run and starts in under 3 minutes on this machine's
8 GB Docker Desktop. 10 GB gives headroom for simulated shoppers plus the chaos
lab; `--lite` (single instances, one broker) needs less (not measured). JVM flags:
`-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError`.

## 5. Module specifications

### 5.1 platform:core — `com.thirdstone.commerce.platform`

| Class | Purpose |
|---|---|
| `PlatformAutoConfiguration` | Registers everything below (exists; fix the two broken references) |
| `ops.TestToolingGuard` | Exists. Outside dev, fail startup if `commerce.payment.gateway-url`, `commerce.carrier.url`, `commerce.testkit.url` or `spring.mail.host` names `simulators`, `testkit` or `mailpit` |
| `web.Caller`, `CallerArgumentResolver` | Exist. **Add**: ignore `X-Auth-*` unless `X-Edge-Token` equals `commerce.edge.shared-secret` (constant-time compare); fail startup if the secret is blank outside tests |
| `web.ApiException`, `ApiErrorHandler`, `RequestContextFilter` | Exist |
| `events.Topics` | Topic names, `dlt(topic)`, `ALL` |
| `events.EventEnvelope` | Record: `eventId, type, version, occurredAt, source, aggregateId, payload (JsonNode)` |
| `events.EventCodec` | `create`, `serialize`, `parse(byte[])` (validates: size ≤ 256 KiB, id and type patterns, version ≥ 1, object payload), `payload(event, Class)`; throws `InvalidEventException` |
| `events.InvalidEventException` | Non-retryable |
| `events.OrderEvents`, `InventoryEvents`, `BillingEvents`, `ShippingEvents`, `CatalogEvents` | Type-name constants and payload records exactly as in architecture §5, with compact constructors validating required fields; shared `Line` and `Address` records |
| `platform-defaults.yml` | Exists. **Change**: readiness group = `db` only (`management.endpoint.health.group.readiness.include=readinessState,db`); fix the stale comment |

Tests: codec accepts every fixture and rejects each malformed case (empty,
non-JSON, missing field, bad type pattern, oversized, non-object payload);
`TestToolingGuard` cases; `Caller` ignores headers without the edge token.

### 5.2 platform:messaging — `com.thirdstone.commerce.platform.messaging`

Auto-configured when a `DataSource` and `KafkaTemplate` are present.

Migration `db/migration/V0_1__messaging.sql`:

```sql
CREATE TABLE outbox_events (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  topic        VARCHAR(128) NOT NULL,
  record_key   VARCHAR(64)  NOT NULL,
  event_type   VARCHAR(64)  NOT NULL,
  payload      MEDIUMBLOB   NOT NULL,      -- the serialized envelope
  traceparent  VARCHAR(64)  NULL,
  created_at   DATETIME(6)  NOT NULL
);
CREATE TABLE processed_events (
  consumer     VARCHAR(64) NOT NULL,
  event_id     VARCHAR(64) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (consumer, event_id),
  KEY idx_processed_at (processed_at)
);
```

| Class | Behaviour |
|---|---|
| `Outbox` | `append(topic, type, aggregateId, payload)`: builds the envelope (source = application name), inserts a row with the current `traceparent` (from `Tracer`), requires an active transaction (`Propagation.MANDATORY`), and registers an after-commit hook that wakes the relay |
| `OutboxRelay` | `SmartLifecycle` with one thread. Each pass, in one transaction on one connection: `SELECT GET_LOCK('outbox:<app>', 0)`; if obtained, select up to 200 rows by id, send each (headers `traceparent`, `event-type`), wait for all acknowledgements (10 s), delete the rows, `RELEASE_LOCK`. Loop immediately if the batch was full, else wait for a wake-up or 500 ms. On send failure: release, log at WARN, back off up to 5 s. Stops within the shutdown budget |
| `OutboxMetrics` | `commerce_outbox_pending`, `commerce_outbox_oldest_age_seconds` (sampled every 5 s), `commerce_outbox_published_total{topic}` |
| `EventRouter` | Built per listener: `EventRouter.for("inventory").on(type, PayloadClass, handler)` and `.onWithPreparation(type, PayloadClass, prepare, apply)` (prepare runs before the transaction and its result is passed to apply) |
| `EventDispatcher` | `dispatch(ConsumerRecord<String, byte[]>, EventRouter)`: parse → find handler (none: count `ignored`) → pre-check `processed_events` → run `prepare` if any → in a transaction: `INSERT IGNORE` into `processed_events` (0 rows: count `duplicate`, return) → handler. Sets MDC (`eventId`, `eventType`, `aggregateId`). Records `commerce_events_consumed_total` and `commerce_event_processing_seconds`. A handler signals "does not apply" by returning normally after a zero-row update and calling `EventContext.ignored(reason)` |
| `KafkaErrorHandling` | `DefaultErrorHandler` bean: `ExponentialBackOffWithMaxRetries(3)` (200 ms × 4), `InvalidEventException` not retryable, recoverer → `<topic>.dlt`, counts `commerce_events_dead_lettered_total{consumer,topic,reason}` and logs at ERROR with the trace id |
| ~~`TopicConfiguration`~~ | Not in the services: topics and ACLs are created by the `kafka-init` job (5.13). `spring.kafka.listener.missing-topics-fatal=true` makes a service fail at startup if a topic it consumes does not exist |
| `ProcessedEventsPurge` | Nightly: delete `processed_events` older than 8 days, in batches |
| `messaging-defaults.yml` | Imported by event-driven services: SASL/SCRAM-SHA-512 client settings (`security.protocol=SASL_PLAINTEXT`, JAAS from `KAFKA_USERNAME` / `KAFKA_PASSWORD`, no default password); producer `acks=all`, idempotence, `lz4`, `linger.ms=5`, `delivery.timeout.ms=30000`; consumer `auto-offset-reset=earliest`, `max.poll.records=200`, `session.timeout.ms=10000`; listener `concurrency=3`, observation on; Hikari max 10, connection timeout 2 s |

Listeners in services look like:

```java
@KafkaListener(topics = Topics.ORDER, groupId = "inventory")
void onOrderEvent(ConsumerRecord<String, byte[]> record) {
    dispatcher.dispatch(record, router);
}
```

The consumer group id is the service name (one group per service per topic is
enough: `groupId = "<service>"`).

Tests (Testcontainers MySQL + Kafka): an appended event is published once the
transaction commits and not if it rolls back; two relays never publish the same
row concurrently and keep id order; a duplicate event id is applied once; a
malformed record lands on the dead-letter topic without retries and the next
record is processed; a handler failing three times lands on the dead-letter
topic; the trace id of the appending thread appears in the consumer.

### 5.3 identity (Bastion) — additive changes only

1. `GET /internal/authz[?require=admin]` — new `GatewayAuthController`. 204 with
   `X-Auth-User-Id`, `X-Auth-Email`, `X-Auth-Role`, `X-Auth-Strong` from the
   `AuthPrincipal`; unauthenticated → the existing JSON 401; `require=admin`
   without role `ADMIN`, or without a strong session when
   `app.security.ops-requires-mfa` (env `OPS_REQUIRE_MFA`, default true), → 403
   with code `ADMIN_MFA_REQUIRED` or plain forbidden. Tests: valid token; no
   token; revoked session; locked user; non-admin; admin without 2FA.
2. Tracing settings in `application.yml` (same property names as the platform
   defaults); `environment` metric tag.
3. Nothing else. Run its full test suite (`./gradlew :services:identity:build`)
   before and after.

### 5.4 catalog

Table `products(sku PK VARCHAR(40), name, description TEXT, category, price_cents,
currency, image_key, active BOOL, available INT NOT NULL DEFAULT 0, created_at,
updated_at)`; indexes on `(active, category)`, `name`.

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /api/v1/catalog/products?category&q&page&size` | public | active products; `Cache-Control: public, max-age=5`; Caffeine 5 s |
| `GET /api/v1/catalog/products/{sku}` | public | same caching |
| `GET /api/v1/catalog/categories` | public | |
| `GET /api/v1/backoffice/catalog/products` | admin | all products |
| `PUT /api/v1/backoffice/catalog/products/{sku}` | admin | upsert; emits `product.upserted`; audit log |

Consumes `STOCK` → `UPDATE products SET available = ? WHERE sku = ?`.

### 5.5 inventory

Tables `stock(sku PK, on_hand INT, reserved INT, updated_at)`,
`reservations(order_id, sku, quantity, status ENUM('RESERVED','COMMITTED','RELEASED'), PRIMARY KEY (order_id, sku))`.

| Event | Handling |
|---|---|
| `product.upserted` | `INSERT IGNORE INTO stock (sku, 0, 0)` |
| `order.placed` | Lock the lines' stock rows `FOR UPDATE` in SKU order; if every line has `on_hand - reserved >= quantity`: add to `reserved`, insert reservations, emit `inventory.reserved` and a `stock-changed` per SKU; else emit `inventory.rejected` (first short SKU) |
| `order.confirmed` | Reservations `RESERVED → COMMITTED`: `on_hand -= q`, `reserved -= q` |
| `order.cancelled` | `RESERVED → RELEASED`: `reserved -= q`; `COMMITTED → RELEASED`: `on_hand += q`; emit `stock-changed` |
| `order.returned` | `on_hand += q` per line (idempotent via processed events); emit `stock-changed` |

Back office: `GET /api/v1/backoffice/inventory/reservations?orderId` (read-only, for the ledger audit), `GET /api/v1/backoffice/inventory/stock`,
`PUT /api/v1/backoffice/inventory/stock/{sku}` `{onHand}` (emits `stock-changed`).
Gauge `commerce_stock_available{sku}`.

### 5.6 order

Tables `orders(id PK CHAR(26), user_id, email, status, total_cents, currency,
payment_method, shipping_address JSON, cancel_reason, tracking_number,
return_tracking_number, idempotency_key, created_at, updated_at, delivered_at,
UNIQUE (user_id, idempotency_key), KEY (user_id, created_at), KEY (status, created_at))`,
`order_lines(order_id, line_no, sku, name, quantity, unit_price_cents)`,
`order_history(id, order_id, status, detail, at)`,
`products(sku PK, name, price_cents, currency, active)`.

| Endpoint | Notes |
|---|---|
| `POST /api/v1/orders` | Body `{lines:[{sku, quantity}], shippingAddress, paymentMethod, expectedTotalCents}`; header `Idempotency-Key` required. 202 `{id, status}`. 400 unknown/inactive SKU or bad quantity; 409 `PRICE_CHANGED` with current lines; 503 + `Retry-After` when shedding load. A repeated key returns the original order |
| `GET /api/v1/orders?page&size` | The caller's orders, newest first |
| `GET /api/v1/orders/{id}` | With lines and history; 404 if not the caller's |
| `POST /api/v1/orders/{id}/cancel` | 409 `NOT_CANCELLABLE` outside `PENDING/AWAITING_PAYMENT/CONFIRMED` |
| `POST /api/v1/orders/{id}/return` | Body `{reason}`; 409 `NOT_RETURNABLE` unless `DELIVERED` within the window |
| `GET /api/v1/backoffice/orders?q&status&page` and `/{id}` | Admin, read-only |

Event handling: the state machine in architecture §6. Saga timeout scheduler
every 5 s (`FOR UPDATE SKIP LOCKED`, batches of 100). Settings:
`commerce.order.saga-timeout` (2 m), `commerce.order.return-window-days` (30),
`commerce.order.max-in-flight` (5000). Metrics: `commerce_orders_total`,
`commerce_orders_by_status` (refreshed every 5 s), `commerce_order_saga_seconds`,
`commerce_revenue_cents_total`, `commerce_refunds_cents_total`.

### 5.7 billing

Table `payments(id PK CHAR(26), order_id UNIQUE, user_id, amount_cents, currency,
status ENUM('AUTHORIZED','FAILED','REFUNDED','CANCELLED'), provider_ref,
failure_reason, created_at, updated_at)`.

| Event | Handling |
|---|---|
| `order.payment-requested` | If a `CANCELLED` marker or any payment exists → ignore. If past `expiresAt` → `payment.failed (EXPIRED)`. Prepare: charge the provider (idempotency key = order id). Apply: insert payment; emit `payment.authorized`, or `payment.failed` with `PAYMENT_DECLINED` (402) or `PAYMENT_UNAVAILABLE` (breaker open / timeouts after one retry) |
| `order.cancelled` | No payment → insert a `CANCELLED` marker row. `AUTHORIZED` → prepare: refund; apply: `REFUNDED`, emit `payment.refunded` |
| `order.returned` | `AUTHORIZED` → refund as above |

Back office: `GET /api/v1/backoffice/billing/payments?orderId|from|to` (read-only, for support and the ledger audit; routed by `storefront-web`).

`PaymentGatewayClient`: `RestClient` with 1 s/3 s timeouts, Resilience4j
`CircuitBreaker` named `payment` bound to Micrometer,
`commerce_external_calls_seconds{target="payment"}`. Setting:
`commerce.payment.gateway-url` (env `PAYMENT_GATEWAY_URL`, required), API key
`PAYMENT_GATEWAY_KEY`. A refund that fails because the provider is unavailable
throws, so the event is retried and, failing that, dead-lettered for replay.

### 5.8 shipping

Table `shipments(id PK CHAR(26), order_id, direction ENUM('OUTBOUND','RETURN'),
status, user_id, email, address JSON, carrier, tracking_number, attempts,
next_attempt_at, created_at, updated_at, UNIQUE (order_id, direction),
KEY (status, next_attempt_at))`.

Handlers only insert or cancel rows (`order.confirmed` → OUTBOUND `PENDING`;
`order.return-requested` → RETURN `PENDING`; `order.cancelled` → OUTBOUND
`PENDING|CREATED` → `CANCELLED`). `ShipmentWorker` every 2 s, `SKIP LOCKED`:
book `PENDING` shipments with the carrier, poll `CREATED/DISPATCHED/IN_TRANSIT`
ones, and emit the shipping events of architecture §5 as states advance;
failures back off (`next_attempt_at`). Back office: `GET /api/v1/backoffice/shipping/shipments?orderId` (read-only). `CarrierClient` like the payment client;
setting `commerce.carrier.url` (env `CARRIER_URL`, required).

### 5.9 notification

Table `notifications(id, event_id UNIQUE, recipient, template, subject, body TEXT,
status ENUM('PENDING','SENT','FAILED'), attempts, next_attempt_at, created_at, sent_at)`.

Handlers render and insert a `PENDING` row for: `order.confirmed`,
`order.cancelled`, `shipment.dispatched`, `shipment.delivered`,
`shipment.return-label-created`, `order.refunded`. `NotificationWorker` sends via
`JavaMailSender` (plain text, 5 s timeouts), backoff up to 24 h, then `FAILED`.
Back office: `GET /api/v1/backoffice/notifications?recipient|from|to` (read-only; no bodies). Metric `commerce_notifications_total{template,outcome}`.

### 5.10 ops

Tables `incidents(id, fingerprint UNIQUE-with-starts_at, alertname, severity,
service, summary, description, runbook_url, status ENUM('FIRING','RESOLVED'),
starts_at, ends_at, acknowledged_by, acknowledged_at, updated_at)`,
`incident_notes(id, incident_id, author, note, at)`.

| Endpoint (`/api/v1/ops/…`, admin) | Source |
|---|---|
| `GET config` | Environment, feature flags (`testkit`), links to Grafana etc. |
| `GET overview` | Prometheus: per-service up/replicas, rate, error rate, p95, lag; system totals; firing incident count |
| `GET services/{name}` and `…/series?metric&range` | Prometheus range queries (whitelisted metric keys → PromQL; never pass client PromQL through) |
| `GET services/{name}/errors` | Loki: last 50 `ERROR` lines |
| `GET kafka` | Admin API: brokers, topics, groups, lag |
| `GET instances` | Every environment: per service, its instances with health, uptime, restarts, memory (Prometheus `up`, `process_*`, `jvm_*`); per broker: id, active controller, leader count, in-sync replicas (admin API); per consumer group: member → partitions; outbox relay lock holder per service (gauge `commerce_outbox_relay_leader`). In dev, merged with the controller's container list (state: running, paused, exited) |
| `POST instances/{id}/{stop\|start\|kill\|pause\|unpause\|restart}` | Dev only: forwards to the controller; 404 elsewhere; writes an incident note |
| `ANY experiments/**` | Dev only: proxy to the controller's experiment API |
| `GET kafka/dead-letters`, `GET kafka/dead-letters/{topic}?limit`, `POST kafka/dead-letters/{topic}/replay` | Short-lived `assign`/`seek` consumer; replay needs `X-Auth-Strong` and writes an incident note |
| `GET business` | Prometheus: funnel, reasons, revenue, saga percentiles, stock |
| `GET incidents`, `POST incidents/{id}/ack`, `POST incidents/{id}/notes` | Own tables |
| `GET live` (SSE) | `ops-live` consumer group: counters per type per second, last 200 event headers |
| `ANY testkit/**` | Proxy to `commerce.testkit.url`; 404 unless environment is dev and the URL is set |

Also: `POST /internal/alerts` (Alertmanager webhook; reachable on the telemetry
network only, never proxied by an edge) upserts incidents; a 15 s job exports
`commerce_kafka_*` gauges and probe metrics.

### 5.11 simulators and testkit (DEV only)

As specified in architecture §11. Testkit API (reached through the ops proxy):
`GET status`, `PUT shoppers` `{count, ordersPerMinute, cancelRate, returnRate,
declineRate, outOfStockRate}`, `GET actors`, `POST actors` `{kind, type, topic,
ratePerSecond}`, `DELETE actors/{id}`, `PUT faults/{payment|carrier}`. Actors are
capped (10 at once, 2 000 records/s each) so the lab cannot exhaust the
developer's machine by accident. Both services depend on `platform:core` only;
testkit adds `spring-kafka` and uses raw `KafkaProducer`/`KafkaConsumer`.

### 5.11a controller and docker-proxy (DEV only)

`docker-proxy`: `tecnativa/docker-socket-proxy` (pin tag and digest) with the
socket mounted read-only and only `CONTAINERS=1`, `POST=1`,
`ALLOW_START=1`, `ALLOW_STOP=1`, `ALLOW_RESTARTS=1` enabled; everything else
(`IMAGES`, `VOLUMES`, `NETWORKS`, `EXEC`, `BUILD`, `SERVICES`, `SWARM`, …) off.
Verify at milestone 9 exactly which flags the pinned version needs for
kill/pause/unpause, and that `exec` and `create` are refused. Network `docker`
only.

`controller` (`com.thirdstone.commerce.controller`, depends on `platform:core`):

| Part | Behaviour |
|---|---|
| `DockerClient` | `RestClient` to `http://docker-proxy:2375`: list containers filtered by label `com.docker.compose.project=<this project>`; start, stop (10 s grace), kill, pause, unpause, restart by container id |
| `InstancePolicy` | Allowlist of Compose services that may be acted on (domain services, identity, edges, `kafka-*`, `mysql`, `simulators`); hard denylist (`controller`, `docker-proxy`, `ops`, `ops-web`, observability). An id is resolved from the fresh container list on every call — never trusted from the request |
| `GET /instances`, `POST /instances/{id}/{action}` | As used by ops |
| `ExperimentRunner` | The catalogue in architecture §11 as code: each experiment is `steadyState()`, `inject()`, `observe(duration)`, `restore()`, `verdict()`. One experiment at a time. `restore()` always runs, including after a failure or a controller restart (state is persisted to a small file volume and reconciled on startup: anything the controller stopped is started again) |
| `LedgerAudit` | The invariants in architecture §11, via the back-office read APIs (as the seeded administrator, through `storefront-web`) and the testkit's journal of every action its shoppers took (`GET testkit/journal?from&to`) |
| `GET /experiments`, `POST /experiments/{name}/run`, `POST /experiments/run-all`, `GET /runs`, `GET /runs/{id}` (JSON and Markdown report) | Console and `scripts/prove.sh` |

The steady-state and impact measurements come from Prometheus (edge-side error
rate and latency) and from the testkit's own client-side counts — the shoppers'
view is the one that counts for "customer impact".

`TestToolingGuard` additionally refuses `commerce.controller.url` outside dev.

### 5.12 Edges and web apps

`web/storefront`: keep Bastion's nginx hardening; replace the single `app`
upstream with `identity`, `catalog`, `order`, `inventory` upstreams; add the
`auth_request` location, micro-cache, limits and back-office routes per
architecture §3. SPA additions: `pages/shop/` (Catalog, Product, Cart, Checkout,
Orders, OrderDetail), `pages/backoffice/` (Products, Stock, Orders),
`api/shop.ts`, `cart/CartContext.tsx` (localStorage, try/catch); always go
through `api/client.ts`; show `X-Request-Id` on errors.

`web/ops-console`: new Vite app sharing Bastion's `api/client.ts` and sign-in
(password, TOTP, passkey); pages per architecture §10. Charts are inline SVG
(no chart library) unless a need appears; if one is added, pin its exact version.
Load the `frontend-design` and `dataviz` skills before building it.

### 5.13 Deployment files

Per architecture §12. Model `deploy/compose.yml` on Bastion's
`docker-compose.yml` (anchors, healthchecks, limits, ingress networks).
`deploy/mysql/roles.sql` becomes a loop over the databases
(`identity, catalog, orders, inventory, billing, shipping, notification, ops`)
creating `<db>`, `<db>_owner`, `<db>_app`, plus an `exporter` login.

**Kafka security** (ADR-024). Listeners: `CLIENT://:9092` `SASL_PLAINTEXT` with
`SCRAM-SHA-512`; `BROKER://:9093` and `CONTROLLER://:9095` `SASL_PLAINTEXT` with
`PLAIN` and a broker credential (static JAAS in the broker config);
`authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer`,
`allow.everyone.if.no.acl.found=false`, `super.users=User:broker;User:kafka-init`.
The `kafka-init` SCRAM credential is created when storage is formatted
(`kafka-storage.sh format --add-scram …`); `deploy/kafka/init.sh`, run by a
one-shot `kafka-init` service that everything event-driven waits for
(`service_completed_successfully`), then creates idempotently: the other SCRAM
users (`kafka-configs.sh --alter --add-config 'SCRAM-SHA-512=[password=…]'`),
the topics, and the ACLs (`kafka-acls.sh`) per the table in architecture §5 —
`Write`+`Describe` on owned topics, `Read`+`Describe` on consumed topics, `Read`
on the service's group (prefixed `<service>`), `Write` on the relevant `.dlt`
topics, `IdempotentWrite` on the cluster for producers. The dev overlay adds a
second init step for `testkit`, `testkit-chaos` and `intruder`. Passwords:
`KAFKA_PASSWORD_<PRINCIPAL>`, required (`${VAR:?}`) outside dev, fixed published
values in `dev.env.example`. **Verify first, at milestone 2, with a single
broker**: SCRAM bootstrap in KRaft and the PLAIN controller listener are the
parts of this plan most likely to need adjustment against Kafka 4.4's
documentation. Kafka:
KRaft combined mode, `auto.create.topics.enable=false`,
`message.max.bytes=1048576`; listeners `INTERNAL://:9092` (events network) and,
in dev only, `EXTERNAL://localhost:9094`.

## 6. Build order and acceptance checks

Each milestone ends runnable. Do not start one until the previous one's checks
pass. Commit at each milestone.

| # | Milestone | Done when |
|---|---|---|
| 0 | **Foundations.** `git init -b main`; `.gitignore` (Bastion's + `deploy/env/*.env`); fix `platform:core` compile errors; build identity in the multi-project build | `./gradlew :services:identity:build` passes with all Bastion tests (MySqlMigrationTest ran, not skipped) |
| 1 | **Platform.** Finish 5.1 and 5.2 with tests | `./gradlew :platform:core:build :platform:messaging:build` green, including the Testcontainers tests |
| 2 | **Walking skeleton.** `services/Dockerfile`; `deploy/compose.yml` with MySQL (all databases and logins), **one Kafka broker with SASL and ACLs and the `kafka-init` job**, identity, catalog, storefront-web (edge with auth check) and Bastion's SPA unchanged; `scripts/stack.sh` | Stack starts healthy; sign up via Mailpit works through the edge; `GET /api/v1/catalog/products` returns `[]`; `/internal` and `/actuator` are 404 at the edge; a request with forged `X-Auth-*` headers is not believed; a Kafka client with no credentials, and one with catalog's credentials writing to the order topic, are both refused |
| 3 | **Order saga, happy path.** inventory, order, billing, simulators; dev overlay | With products and stock inserted through the back-office API, `POST /orders` reaches `CONFIRMED`; every failure branch reaches `CANCELLED` with the right reason and stock restored |
| 4 | **Fulfilment and returns.** shipping, notification; cancel and return flows | An order reaches `DELIVERED`, is returned and reaches `REFUNDED`; all six emails arrive in Mailpit; cancelling a confirmed order refunds and restocks |
| 5 | **Observability.** Prometheus, Alertmanager, Loki, Tempo, Alloy, Grafana; rules and dashboards | One trace in Tempo spans `POST /orders` through the confirmation email; a log line links to its trace; killing a service fires `ServiceDown` within 2 min |
| 6 | **Ops service and console.** 5.10 and the console pages except Simulator/Chaos | Overview reflects a stopped service within one scrape; dead letters are browsable; an alert appears as an incident and can be acknowledged |
| 7 | **Testkit.** Seeding, simulated shoppers (with a journal of every action), problem actors incl. the intruder, console pages | Fresh `stack.sh dev up` yields a browsable shop with two accounts; 20 shoppers sustain orders, cancellations and returns; each problem actor produces the response in architecture §11 while the smoke test still passes |
| 8 | **Storefront shop and back office.** | A person can sign up, shop, check out, watch the order progress, cancel, and return; an admin can add a product and stock in an empty environment |
| 9 | **HA topology and environments.** `compose.ha.yml` (default for dev), `--lite`, int/qa/prod overlays, env examples, `verify-clean-deployments.sh` | `stack.sh <int\|qa\|prod> config` fails without the required variables and, with them, contains no dev-only service, network, setting or Docker-socket mount (other than Alloy's); DEV starts with 2 replicas and 3 brokers and all are healthy |
| 9a | **Instance control.** `docker-proxy`, `controller` (instances API), ops endpoints, console Instances page | Every replica and broker is listed with correct state; stop/kill/pause/restart work from the console and are reflected within seconds; the controller refuses denylisted services and unknown ids; the proxy refuses `exec` and `create` |
| 9b | **Resilience proofs.** `ExperimentRunner`, `LedgerAudit`, back-office read endpoints, console page, `scripts/prove.sh` | `scripts/prove.sh` runs the whole catalogue and every experiment passes with a clean audit; deliberately breaking a guarantee (e.g. disabling the processed-events check in a scratch build) makes the corresponding experiment fail — proving the proofs can fail |
| 10 | **CI/CD and docs.** Workflows (CI runs `prove.sh --quick`: replica crash, broker crash, poison data, intruder); `docs/api.md`, `docs/configuration.md`; refresh `operations.md` with what was actually observed; update `docs/identity/` paths | CI green on a clean clone; README quickstart works verbatim |
| 11 | **Load baseline.** Drive the simulator at increasing rates | The latency targets in architecture §8 are confirmed or replaced by measured numbers, with the bottleneck named |

## 7. Definition of done

- Every acceptance check above passes and is automated where it can be
  (`scripts/smoke.sh`, tests).
- `./gradlew build` and both SPA builds are green; no Bastion test was skipped,
  deleted or loosened.
- `scripts/verify-clean-deployments.sh` and `scripts/verify-stack.sh` pass.
- The documents match the system: anything built differently from the design is
  reflected in `architecture.md` and, if it was a choice, in a new decision
  record.
- Anything not done is listed in the README's status section, not left implied.
