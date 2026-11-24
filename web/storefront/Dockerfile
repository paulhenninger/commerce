# The storefront edge: the built SPA, served by nginx, which also authenticates
# and proxies /api.
#
# Base images pinned to a version and digest (see services/Dockerfile).
# Node stays on an LTS line (26); CI fails if this and ci.yml disagree.
FROM node:26.13.1-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

# nginx built to run as an ordinary user (uid 101) on port 8080, on its stable
# branch (even minor: 1.30, 1.32 …); Dependabot proposes only patches here.
# Alpine's security fixes are applied as root, the only step that is.
FROM nginx/nginx-unprivileged:1.30.8-alpine@sha256:637c35e46af7bc0dd3d3bcf7162e004e13ab3b370fde7cd416e2ac410b5a985a
USER root
RUN apk upgrade --no-cache && mkdir -p /etc/nginx/upstreams /etc/nginx/routes
# Rate limits are fixed when the image is built: generous defaults that a
# deployment overrides by building with other values.
ARG API_RATE=50
ARG API_BURST=100
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/trusted-proxies.conf /etc/nginx/trusted-proxies.conf
COPY nginx/security-headers.conf nginx/api-headers.conf nginx/proxy-base.conf nginx/proxy-public.conf \
     nginx/proxy-authed.conf nginx/proxy-caller.conf nginx/authz.conf /etc/nginx/snippets/
COPY nginx/upstreams/ /etc/nginx/upstreams/
COPY nginx/routes/ /etc/nginx/routes/
RUN rm -f /etc/nginx/upstreams/README.md /etc/nginx/routes/README.md \
 && sed -i -e "s/\${API_RATE}/${API_RATE}/g" -e "s/\${API_BURST}/${API_BURST}/g" \
      /etc/nginx/nginx.conf /etc/nginx/routes/*.conf
COPY nginx/start.sh /etc/nginx/start.sh
USER 101
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
# Not the image's start-up scripts: they rewrite config files, which a read-only
# filesystem refuses. start.sh only writes the edge secret to /tmp, then runs nginx.
ENTRYPOINT ["/bin/sh", "/etc/nginx/start.sh"]
