FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# The unprivileged variant rather than the stock image plus a USER line. It is the same nginx,
# already listening on 8080 and already keeping its pid and every temporary path under /tmp — which
# is what a root-owned /var/run and /var/cache otherwise make impossible to drop privileges past.
# Nothing here serves anything but static files, so there is no reason for it to run as root.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/janus-security-headers.conf
USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --quiet --spider http://127.0.0.1:8080/index.html || exit 1
