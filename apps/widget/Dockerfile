# The chat widget: the loader websites embed (servora-widget.js) and the iframe page, served by
# unprivileged nginx, which also forwards the public widget API to the core API.
FROM node:24.13.0-alpine3.22 AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24
# Security updates published since the base image was built.
USER root
# nginx 1.30.7 fixes six high and critical advisories published 2026-11-18, including one in the
# resolver this configuration uses. No 1.30.7 base image exists yet, so the package comes from
# nginx.org, signed with the key the base image already trusts. Remove the last two commands once
# the base image tag is 1.30.7 or later and two weeks old. The optional modules (geoip, image-filter,
# njs, xslt) are never loaded by this configuration and are pinned to the old version, so they go.
RUN apk upgrade --no-cache \
    && apk del --no-cache nginx-module-geoip nginx-module-image-filter nginx-module-njs nginx-module-xslt \
    && apk add --no-cache --repository https://nginx.org/packages/alpine/v3.24/main "nginx=1.30.7-r1"
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
