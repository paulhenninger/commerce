#!/usr/bin/env bash
# Checks that a running DEV stack is as hardened as the Compose files say: what
# the two edges serve and refuse, what each container can reach, how the
# services sign in to MySQL, and the runtime restrictions on every container. A
# restriction that is written down is not one that holds; this is what the
# Security workflow runs against the images it scanned (Bastion's
# scripts/verify-stack.sh, extended to every service of this system).
#
#   scripts/stack.sh dev up -d --build --wait
#   scripts/verify-stack.sh               # exits non-zero on any failure
#
# The "cannot reach the internet" checks fail on Docker Desktop, whose VM
# forwards traffic for containers on networks that Linux keeps closed; they pass
# on Linux (CI).
set -u
export MSYS_NO_PATHCONV=1

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/deploy/env/dev.env"
[ -f "$env_file" ] || { echo "verify-stack: $env_file not found; start the stack with scripts/stack.sh dev first" >&2; exit 1; }
setting() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2- | tr -d '\r'; }
stack() { "$root/scripts/stack.sh" dev "$@"; }

STOREFRONT="http://127.0.0.1:$(setting STOREFRONT_PORT)"
CONSOLE="http://127.0.0.1:$(setting OPS_CONSOLE_PORT)"
failed=0

ok()     { echo "  ok   $1"; }
bad()    { echo "  FAIL $1"; failed=1; }
check()  { if eval "$2" >/dev/null 2>&1; then ok "$1"; else bad "$1"; fi; }
refuse() { if eval "$2" >/dev/null 2>&1; then bad "$1"; else ok "$1"; fi; }
in_c()   { stack exec -T "$1" sh -c "$2"; }

# The domain services, and what each may reach beyond its own networks.
SERVICES="identity catalog inventory order billing shipping notification ops"

for edge in "storefront-web $STOREFRONT" "ops-web $CONSOLE"; do
  name=${edge%% *}; site=${edge#* }
  echo "== What $name serves ($site)"
  check  "serves the SPA"                          "curl -fsS --max-time 10 $site/ | grep '<div id=\"root\"'"
  check  "client-side routes fall back to the SPA" "curl -fsS --max-time 10 $site/orders/x | grep '<div id=\"root\"'"
  check  "pages carry a strict CSP"                "curl -fsSI --max-time 10 $site/ | grep -i \"^content-security-policy: default-src 'self'\""
  check  "pages carry HSTS"                        "curl -fsSI --max-time 10 $site/ | grep -i '^strict-transport-security: max-age='"
  check  "pages forbid framing"                    "curl -fsSI --max-time 10 $site/ | grep -i '^x-frame-options: DENY'"
  check  "API answers forbid everything a page may do" \
         "curl -sSI --max-time 10 $site/api/v1/auth/config | grep -i \"^content-security-policy: default-src 'none'\""
  refuse "nginx hides its version"                 "curl -fsSI --max-time 10 $site/ | grep -i '^server: nginx/'"
  refuse "the actuator is not reachable"           "curl -fsS --max-time 10 $site/actuator/health | grep status"
  refuse "internal endpoints are not reachable"    "curl -fsS --max-time 10 $site/internal/authz | grep -v '<div id=\"root\"'"
  refuse "an API call without a token gets through" "curl -fsS --max-time 10 $site/api/v1/orders"
  marker="never-logged-$$-$name"
  curl -fsS --max-time 10 -o /dev/null "$site/reset-password?token=$marker" || true
  refuse "query strings stay out of the access log" "stack logs $name 2>&1 | grep $marker"
  curl -fsS --max-time 10 -o /dev/null -H 'X-Forwarded-For: 203.0.113.77' "$site/" || true
  refuse "a forged X-Forwarded-For is not believed" "stack logs $name 2>&1 | grep '^[^|]*| 203.0.113.77 \['"
done

echo "== What each container can reach"
internet() { in_c "$1" 'wget -q -T 5 -O /dev/null http://1.1.1.1/'; }
check "identity reaches the internet (mail relay, breach check)" "internet identity"
for c in storefront-web ops-web catalog inventory order billing shipping notification ops; do
  refuse "$c cannot reach the internet" "internet $c"
done
# billing and shipping reach the simulators in DEV; real providers only outside it.
check  "billing reaches the payment simulator"   "in_c billing 'nc -z -w 5 simulators 8080'"
check  "storefront-web reaches the services"     "in_c storefront-web 'nc -z -w 5 catalog 8080'"
check  "ops-web reaches ops"                     "in_c ops-web 'nc -z -w 5 ops 8080'"
for edge in storefront-web ops-web; do
  refuse "$edge cannot reach MySQL"              "in_c $edge 'nc -z -w 3 mysql 3306'"
  refuse "$edge cannot reach Kafka"              "in_c $edge 'nc -z -w 3 kafka-1 9092'"
done
refuse "catalog cannot reach the Docker proxy"   "in_c catalog 'nc -z -w 3 docker-proxy 2375'"
refuse "ops cannot reach the Docker proxy"       "in_c ops 'nc -z -w 3 docker-proxy 2375'"

echo "== How the services sign in to MySQL"
sql() { printf '%s;\n' "$1" | in_c mysql 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N' 2>/dev/null; }
sessions=$(sql "select group_concat(distinct concat(t.processlist_user, '/', coalesce(nullif(s.variable_value, ''), 'plain')))
  from performance_schema.threads t
  left join performance_schema.status_by_thread s
    on s.thread_id = t.thread_id and s.variable_name = 'Ssl_version'
  where t.processlist_user is not null and t.processlist_user not in ('root', 'event_scheduler')")
if [ -z "$sessions" ] || [ "$sessions" = "NULL" ]; then
  bad "the services have database sessions"
elif echo "$sessions" | tr ',' '\n' | grep -vE '^[a-z]+_app/TLS|^exporter/TLS' >/dev/null; then
  bad "only <database>_app logins and the exporter, over TLS, hold sessions (found: $sessions)"
else
  ok "only <database>_app logins and the exporter, over TLS, hold sessions ($sessions)"
fi
for db in identity catalog orders inventory billing shipping notification ops; do
  grants=$(sql "show grants for ${db}_app@'%'")
  if echo "$grants" | grep -q "GRANT SELECT, INSERT, UPDATE, DELETE ON \`$db\`" \
     && ! echo "$grants" | grep -qE 'ALL PRIVILEGES|CREATE|DROP|ALTER|\*\.\*` TO|ON \*\.\* TO .*(SELECT|INSERT)'; then
    ok "${db}_app can only read and write rows of $db"
  else
    bad "${db}_app has more than row access to $db: $grants"
  fi
done
mysql_image=$(stack config --images 2>/dev/null | grep '^mysql:' | head -1)
client() { docker run --rm --network commerce-dev_data --entrypoint mysql "$mysql_image" -h mysql "$@" -e "${SQL:-select 1}"; }
app_pw=$(setting CATALOG_DB_PASSWORD)
refuse "root cannot sign in over the network"    "client -uroot -p'$(setting DB_ADMIN_PASSWORD)'"
check  "catalog_app signs in over TLS"           "client -ucatalog_app -p'$app_pw' --ssl-mode=REQUIRED catalog"
refuse "an unencrypted connection is refused"    "client -ucatalog_app -p'$app_pw' --ssl-mode=DISABLED catalog"
refuse "catalog_app cannot create a table"       "SQL='create table pwned (id int)' client -ucatalog_app -p'$app_pw' --ssl-mode=REQUIRED catalog"
refuse "catalog_app cannot read another service's database" \
       "SQL='select count(*) from orders.orders' client -ucatalog_app -p'$app_pw' --ssl-mode=REQUIRED catalog"

echo "== Runtime restrictions on every container"
for c in $(stack ps -aq); do
  line=$(docker inspect -f '{{.Name}} user={{.Config.User}} ro={{.HostConfig.ReadonlyRootfs}} caps={{.HostConfig.CapDrop}} opts={{.HostConfig.SecurityOpt}} privileged={{.HostConfig.Privileged}} mem={{.HostConfig.Memory}} pids={{.HostConfig.PidsLimit}}' "$c")
  # Alloy is the one container that runs as root (implementation plan §4): the
  # rest of its restrictions still hold, so check those and nothing less.
  case "$line" in /commerce-dev-alloy-1\ user=\ *) line="${line/user= /user=root(documented) }" ;; esac
  if echo "$line" | grep -E 'user=[^ ]+ ro=true caps=\[ALL\] opts=\[no-new-privileges:true\] privileged=false mem=[1-9][0-9]* pids=[1-9]' >/dev/null \
     && ! echo "$line" | grep -E 'user=(0|root)(:| )' >/dev/null; then
    ok "$line"
  else
    bad "$line"
  fi
done
# Only Alloy and the DEV docker-proxy may hold the Docker socket (AGENTS.md).
for c in $(stack ps -aq); do
  name=$(docker inspect -f '{{.Name}}' "$c")
  if docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' "$c" | grep -q 'docker.sock'; then
    case "$name" in
      */commerce-dev-alloy-1|*/commerce-dev-docker-proxy-1) ok "$name may mount the Docker socket" ;;
      *) bad "$name mounts the Docker socket" ;;
    esac
  fi
done

[ "$failed" -eq 0 ] && echo "== All checks passed" || echo "== Some checks FAILED"
exit $failed
