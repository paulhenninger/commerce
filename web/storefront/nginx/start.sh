#!/bin/sh
# Starts nginx with the edge's shared secret.
#
# The filesystem is read-only and the image's own template step is not used, so
# the one value that cannot be baked into the image is written to /tmp (memory
# only) as a snippet the proxy configuration includes.
set -eu

case "${EDGE_SHARED_SECRET:-}" in
  "") echo "edge: EDGE_SHARED_SECRET is not set" >&2; exit 1 ;;
  *[!A-Za-z0-9_.-]*) echo "edge: EDGE_SHARED_SECRET may only contain A-Z a-z 0-9 _ . -" >&2; exit 1 ;;
esac
if [ "${#EDGE_SHARED_SECRET}" -lt 32 ]; then
  echo "edge: EDGE_SHARED_SECRET must be at least 32 characters" >&2; exit 1
fi

mkdir -p /tmp/nginx
umask 077
printf 'proxy_set_header X-Edge-Token "%s";\n' "$EDGE_SHARED_SECRET" > /tmp/nginx/edge-token.conf
unset EDGE_SHARED_SECRET
exec nginx -g 'daemon off;'
