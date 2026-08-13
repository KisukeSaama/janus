FROM node:22-alpine AS ui
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=ui /app/dist /usr/share/nginx/html
COPY deploy/nginx/prod.conf /etc/nginx/conf.d/default.conf
COPY frontend/security-headers.conf /etc/nginx/janus-security-headers.conf
EXPOSE 80 443
