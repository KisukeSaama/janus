FROM node:24-alpine AS ui
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

# The unprivileged variant, same as the console image. It keeps its pid and every temporary path
# under /tmp, which is what makes running as a non-root user possible at all; the two listeners move
# above 1024 for the same reason, and Compose publishes 80 and 443 in front of them so nothing
# outside the container changes.
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY --from=ui /app/dist /usr/share/nginx/html
COPY deploy/nginx/prod.conf /etc/nginx/conf.d/default.conf
COPY frontend/security-headers.conf /etc/nginx/janus-security-headers.conf
USER 101
EXPOSE 8080 8443
