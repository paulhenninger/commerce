# The ops console edge: the built console SPA, served by nginx, which also
# authenticates and proxies /api. Base images pinned to a version and digest.
FROM node:26.13.1-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

FROM nginx/nginx-unprivileged:1.30.8-alpine@sha256:637c35e46af7bc0dd3d3bcf7162e004e13ab3b370fde7cd416e2ac410b5a985a
USER root
RUN apk upgrade --no-cache
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/trusted-proxies.conf /etc/nginx/trusted-proxies.conf
COPY nginx/security-headers.conf nginx/api-headers.conf nginx/proxy-base.conf nginx/proxy-public.conf \
     nginx/proxy-authed.conf nginx/proxy-caller.conf nginx/authz.conf /etc/nginx/snippets/
COPY nginx/start.sh /etc/nginx/start.sh
USER 101
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/bin/sh", "/etc/nginx/start.sh"]
