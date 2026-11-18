# Configuration

Every setting comes from the environment file of the deployment,
`deploy/env/<env>.env`, which `scripts/stack.sh <env>` hands to Docker Compose.
The files are git-ignored; the committed `<env>.env.example` next to each is the
list of what that environment needs.

- **DEV**: `scripts/stack.sh dev` creates `dev.env` from `dev.env.example` on
  first use, replacing every `__GENERATE__` with a fresh random value, and later
  appends settings that were added to the example. Nothing to fill in.
- **INT, QA, PROD**: copy the example, fill in every value, keep the real file in
  your secret store and on the host (CD never copies it). Compose refuses to
  start while a required value is empty; identity refuses development secrets on
  a non-local address (`DeploymentSafetyCheck`); a service refuses any setting
  that points at a test facility (`TestToolingGuard`).

Secrets are never logged. A shell variable of the same name overrides the file
(Compose's rule), which is how CD sets `IMAGE_TAG`. The service's own settings
(Spring properties behind these variables) are in each service's
`application.yml`; identity's are described in
[identity/configuration.md](identity/configuration.md).

## Addresses and ports

Every published port is bound to `127.0.0.1`; put a TLS terminator in front.

| Setting | DEV | Meaning |
|---|---|---|
| `APP_BASE_URL` | `http://localhost:8081` | Where customers reach the shop (links in emails, passkey origin, CORS) |
| `STOREFRONT_PORT` | `8081` | The storefront edge |
| `OPS_CONSOLE_PORT` | `8082` | The ops console edge |
| `PASSKEY_EXTRA_ORIGINS` | `http://localhost:8082` | The console's origin, so sign-in and passkeys work there too |
| `GRAFANA_PORT`, `GRAFANA_PUBLIC_URL` | `3000`, `http://localhost:3000` | Grafana, and the address the console links to |
| `PROMETHEUS_PORT`, `ALERTMANAGER_PORT` | `9090`, `9093` | DEV only: published for debugging |
| `SIMULATORS_PORT` | `8090` | DEV only: the payment and carrier simulators |
| `MAILPIT_UI_PORT` | `8025` | DEV only: Mailpit's web page |
| `COMMERCE_NET` | `10.77` (dev), `10.78` int, `10.79` qa, `10.80` prod | The private /16 the stack's networks are carved from, so environments side by side never overlap (set by `stack.sh`) |

## Images

| Setting | Default | Meaning |
|---|---|---|
| `IMAGE_REGISTRY` | empty (local images) | Prefix of this repository's images, e.g. `ghcr.io/paulhenninger/`; CD and `scripts/deploy.sh` set it |
| `IMAGE_TAG` | `local` | The release: CD tags every image with the first 12 characters of the commit SHA |

## Accounts and security

| Setting | Meaning |
|---|---|
| `BOOTSTRAP_ADMIN_EMAIL` | The first account verified with this address becomes an administrator |
| `JWT_SECRET`, `DATA_ENCRYPTION_KEY` | Identity's signing and encryption keys (`openssl rand -base64 32`) |
| `EDGE_SHARED_SECRET` | Shared by the edges and the services: a service believes identity headers only with it (32+ of `A-Z a-z 0-9 _ . -`) |
| `ADMIN_REQUIRES_MFA`, `OPS_REQUIRE_MFA` | Back office and ops need a two-factor session; also gates dead-letter replay. Default `true`; DEV turns both off for the seeded administrator; PROD forces them on |
| `SIGNUP_CHECK_MX` | Refuse sign-ups whose email domain has no mail server (`false` in DEV: Mailpit has none) |
| `CORS_ORIGINS` | Default `APP_BASE_URL` plus `PASSKEY_EXTRA_ORIGINS` |
| `EDGE_API_RATE`, `EDGE_API_BURST` | Storefront edge rate limit per client address: requests per second (default 50) and burst (100) |
| `DEV_ADMIN_PASSWORD`, `DEV_SHOPPER_PASSWORD` | DEV only: `admin@example.com` (made at startup) and `shopper@example.com` and the simulated shoppers (made when test data and shoppers are switched on, ADR-033) |

## External systems

| Setting | Meaning |
|---|---|
| `PAYMENT_GATEWAY_URL`, `PAYMENT_GATEWAY_KEY` | The payment provider (DEV: the simulator; refused anywhere else) |
| `CARRIER_URL`, `CARRIER_KEY` | The carrier (DEV: the simulator) |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASS`, `MAIL_SMTP_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM` | Outgoing mail (DEV: Mailpit, no credentials) |

## MySQL

One server; one database per service, each with two logins (ADR-001,
`deploy/mysql/roles.sql`): `<db>_owner` runs that service's migrations, `<db>_app`
only reads and writes its rows. Both must use TLS.

| Setting | Meaning |
|---|---|
| `DB_ADMIN_PASSWORD` | MySQL's root (never reachable over the network) |
| `DB_EXPORTER_PASSWORD` | The metrics exporter's read-only login |
| `<DB>_DB_OWNER_PASSWORD`, `<DB>_DB_PASSWORD` | Owner and app login of each database: `IDENTITY`, `CATALOG`, `ORDERS`, `INVENTORY`, `BILLING`, `SHIPPING`, `NOTIFICATION`, `OPS` |

## Kafka

Every client authenticates as its own principal (SASL/SCRAM) and is allowed only
what its role needs (ADR-024, `deploy/kafka/`).

| Setting | Meaning |
|---|---|
| `KAFKA_PASSWORD_<PRINCIPAL>` | One per principal: `BROKER`, `INIT`, `CATALOG`, `ORDER`, `INVENTORY`, `BILLING`, `SHIPPING`, `NOTIFICATION`, `OPS`; DEV adds `TESTKIT`, `TESTKIT_CHAOS` and `INTRUDER` (the chaos lab's unauthorised client). 12+ of `A-Z a-z 0-9 _ . -` |
| `KAFKA_CLUSTER_ID` | The KRaft cluster id (a base64 UUID: `kafka-storage.sh random-uuid`); outside DEV set your own |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_QUORUM_VOTERS`, `KAFKA_REPLICATION_FACTOR`, `KAFKA_MIN_INSYNC_REPLICAS` | Set by `stack.sh`: one broker with `--lite` and INT; three brokers, three copies, two in sync in the HA topology |

## Behaviour

| Setting | Default | Meaning |
|---|---|---|
| `ORDER_SAGA_TIMEOUT` | `2m` | An order still waiting for stock or payment after this long is cancelled (`TIMEOUT`) |
| `ORDER_MAX_IN_FLIGHT` | `5000` | Orders in flight above which `POST /orders` answers `503` + `Retry-After` |
| `RETURN_WINDOW_DAYS` | `30` | How long after delivery a return may be asked for |
| `TRACE_SAMPLING` | `1.0`; PROD `0.1` | Share of requests traced |
| `LOG_LEVEL` | `INFO` | Root log level of every service |
| `ALERTMANAGER_CONFIG` | `deploy/observability/alertmanager/alertmanager.yml` | Alertmanager file with your receivers (pager, chat), kept outside the repository; the default sends alerts only to the ops service |
| `DOCKER_SOCKET_GID` | found by `stack.sh` | DEV only: the group that may use the Docker socket, for `docker-proxy` (`0` on Docker Desktop, the `docker` group's id on Linux) |

## CD (GitHub)

| Where | Name | Meaning |
|---|---|---|
| Repository variable | `CD_ENVIRONMENTS` | The environments that exist, e.g. `dev,int,qa,prod`; one not listed is skipped |
| Environment variables | `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_PATH` | The host, the user CD signs in as, and the directory holding `deploy/` and `scripts/` (and `deploy/env/<env>.env`) |
| Environment secrets | `DEPLOY_SSH_KEY`, `DEPLOY_KNOWN_HOSTS` | A private key allowed only to that user, and the host's public key line (`ssh-keyscan -t ed25519 <host>`, checked by hand) |
| Environment protection | required reviewers | On `qa` and `prod`; `prod` is also limited to version tags (`v*.*.*`) |
