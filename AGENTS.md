# Notes for AI coding agents

Commerce is an event-driven e-commerce system: Spring Boot 4.1 / Java 25
services (`services/`, `platform/`), Kafka, MySQL 9.7, two React SPAs behind
nginx edges (`web/`), Docker Compose (`deploy/`). Base package
`com.thirdstone.commerce`.

**The design is written down and is the source of truth. Read, in order:**

1. [docs/architecture.md](docs/architecture.md) — what the system is
2. [docs/decisions.md](docs/decisions.md) — why; settled unless marked Open
3. [docs/implementation-plan.md](docs/implementation-plan.md) — what exists,
   what to build next, conventions, verified facts, acceptance checks
4. [docs/design-review.md](docs/design-review.md) — open decisions; ask the owner
   before acting on one

Work milestone by milestone (implementation plan §6). Do not start a milestone
until the previous one's checks pass. Update §1 "Current state" as you go.

## Commands

```bash
./gradlew build                                # everything; needs Docker for Testcontainers
./gradlew :services:order:test --tests '*Saga*'
./gradlew :services:identity:build             # Bastion's full suite
(cd web/storefront && npm ci && npm run build)
scripts/stack.sh dev up -d --build --wait      # the DEV stack
scripts/smoke.sh                               # end-to-end check against a running DEV stack
scripts/verify-clean-deployments.sh            # INT/QA/PROD contain no test tooling
```

## Rules

- **If you would build something differently from the design, stop and change
  the design first**: update `architecture.md`, add a decision record, then code.
  Never leave the documents describing a system that does not exist.
- **Test tooling is DEV-only.** Test data, simulated shoppers, simulators,
  Mailpit and chaos tooling live only in `services/testkit`,
  `services/simulators` and `deploy/compose.dev.yml`. Never add test data, test
  endpoints or "if dev" branches to a deployed service. Never weaken
  `TestToolingGuard` or `scripts/verify-clean-deployments.sh`.
- **Only `docker-proxy` (dev) and Alloy may mount the Docker socket.** Instance
  control goes console → ops → controller → docker-proxy; never widen the
  proxy's permissions or the controller's allowlist to include itself, ops or
  the observability stack.
- **Kafka is least-privilege.** A new topic or consumer needs its ACL in
  `deploy/kafka/`; never give a service write access to a topic it does not own.
- **Services talk only through Kafka events.** No service calls another
  service's API. Events are written only through the outbox, inside the
  transaction that changes state; consumed only through `EventRouter`.
- **No external call inside a database transaction.**
- **Identity is Bastion: additive changes only.** Never weaken a control to make
  something pass — no skipping or loosening its tests, CSP, rate limits,
  `DeploymentSafetyCheck` or container restrictions. Never edit an applied
  Flyway migration anywhere; add a new one. See `docs/identity/security-model.md`.
- New endpoints are authenticated by default, scoped by `caller.userId()`;
  another user's resource is a 404. Back-office and ops endpoints require an
  administrator.
- Secrets come from the environment, are never logged and never committed.
- Runtimes stay on LTS lines (Java 25, Node 26, MySQL 9.7). Images are pinned by
  digest and GitHub Actions by commit SHA.
- Report honestly: say what was run and what was skipped. A Testcontainers test
  skipped because Docker was down did not pass.
- On Docker Desktop, the "cannot reach the internet" checks in
  `scripts/verify-stack.sh` fail by design of its networking; they pass on Linux CI.
