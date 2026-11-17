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
RUN apk upgrade --no-cache
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
