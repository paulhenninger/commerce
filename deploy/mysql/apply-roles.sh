#!/bin/sh
# Creates every Commerce database and its two logins (roles.sql), as the database
# administrator over the database's own socket. deploy/compose.yml's mysql-init
# service runs it on every start; it is idempotent.
#
# For each database <db> it expects <DB>_DB_OWNER_PASSWORD and <DB>_DB_PASSWORD
# (e.g. ORDERS_DB_OWNER_PASSWORD, ORDERS_DB_PASSWORD), and ADMIN_PASSWORD for the
# administrator. EXPORTER_PASSWORD, if set, creates the metrics exporter's login.
set -eu

DATABASES="identity catalog orders inventory billing shipping notification ops"

# A password as a MySQL string literal: backslashes and quotes doubled, so no
# password can end the string early.
quote() {
  escaped=$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g")
  # A failed escape must stop here, not quietly become an empty password.
  [ -n "$escaped" ] || { echo "apply-roles: could not quote a password" >&2; exit 1; }
  printf "'%s'" "$escaped"
}

admin() {
  MYSQL_PWD="$ADMIN_PASSWORD" mysql --protocol=socket -uroot "$@"
}

for db in $DATABASES; do
  upper=$(printf '%s' "$db" | tr '[:lower:]' '[:upper:]')
  eval "owner_password=\${${upper}_DB_OWNER_PASSWORD:-}"
  eval "app_password=\${${upper}_DB_PASSWORD:-}"
  # An empty password would unlock the login with no password at all.
  [ -n "$owner_password" ] || { echo "apply-roles: ${upper}_DB_OWNER_PASSWORD is empty" >&2; exit 1; }
  [ -n "$app_password" ] || { echo "apply-roles: ${upper}_DB_PASSWORD is empty" >&2; exit 1; }

  admin -e "CREATE DATABASE IF NOT EXISTS \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
  init="SET @owner_password = $(quote "$owner_password"), @app_password = $(quote "$app_password")"
  admin --init-command="$init" "$db" < /commerce/roles.sql
  echo "apply-roles: $db ready (${db}_owner, ${db}_app)"
done

# Read-only server statistics for Prometheus: no access to any Commerce data.
if [ -n "${EXPORTER_PASSWORD:-}" ]; then
  admin -e "CREATE USER IF NOT EXISTS 'exporter'@'%' ACCOUNT LOCK;
            REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'exporter'@'%';
            GRANT PROCESS, REPLICATION CLIENT ON *.* TO 'exporter'@'%';
            GRANT SELECT ON performance_schema.* TO 'exporter'@'%';
            ALTER USER 'exporter'@'%' IDENTIFIED BY $(quote "$EXPORTER_PASSWORD") REQUIRE SSL
              WITH MAX_USER_CONNECTIONS 3 ACCOUNT UNLOCK"
  echo "apply-roles: exporter ready"
fi
