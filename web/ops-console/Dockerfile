# The ops console edge: the built console SPA, served by nginx, which also
# authenticates and proxies /api. Base images pinned to a version and digest.
FROM node:26.13.0-alpine@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
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
