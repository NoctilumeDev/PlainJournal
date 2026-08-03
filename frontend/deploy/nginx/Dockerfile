FROM node:24-alpine AS workspace

WORKDIR /workspace

RUN corepack enable \
    && corepack prepare pnpm@11.9.0 --activate

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages ./packages
COPY storefront-web ./storefront-web
COPY admin-web ./admin-web

RUN pnpm install --frozen-lockfile

FROM workspace AS storefront-build
RUN pnpm --filter @plain-journal/storefront-web build

FROM workspace AS admin-build
RUN pnpm --filter @plain-journal/admin-web build

FROM nginx:1.28-alpine AS runtime

ARG PLAINJOURNAL_OCI_CREATED=unknown
ARG PLAINJOURNAL_OCI_REVISION=unknown
ARG PLAINJOURNAL_OCI_SOURCE=https://github.com/NoctilumeDev/PlainJournal
ARG PLAINJOURNAL_OCI_VERSION=local

LABEL org.opencontainers.image.created="${PLAINJOURNAL_OCI_CREATED}" \
    org.opencontainers.image.revision="${PLAINJOURNAL_OCI_REVISION}" \
    org.opencontainers.image.source="${PLAINJOURNAL_OCI_SOURCE}" \
    org.opencontainers.image.vendor="Plain Journal" \
    org.opencontainers.image.version="${PLAINJOURNAL_OCI_VERSION}"

ENV PLAINJOURNAL_GATEWAY_HOST=host.docker.internal \
    PLAINJOURNAL_GATEWAY_PORT=18000 \
    PLAINJOURNAL_LISTEN_PORT=8080

COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template

EXPOSE 8080
STOPSIGNAL SIGQUIT

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O - http://127.0.0.1:8080/ >/dev/null || exit 1

FROM runtime AS storefront
LABEL org.opencontainers.image.title="PlainJournal Storefront" \
    org.opencontainers.image.description="PlainJournal customer storefront static application"
COPY --from=storefront-build /workspace/storefront-web/dist /usr/share/nginx/html

FROM runtime AS admin
LABEL org.opencontainers.image.title="PlainJournal Admin" \
    org.opencontainers.image.description="PlainJournal operations and governance static application"
COPY --from=admin-build /workspace/admin-web/dist /usr/share/nginx/html
