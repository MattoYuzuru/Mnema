# --- build stage ---
FROM node:22.23.2-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /app

# Dependency resolution must match the tested lockfile exactly.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --fund=false

# Теперь исходники
COPY . .

# Use the repository-local Angular CLI selected by package-lock.json.
RUN npm run build

# --- serve stage ---
FROM nginx:1.31.4-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913

# Retain the reviewed nginx base while installing the first fixed Alpine OpenSSL build.
# Exact package versions prevent unreviewed drift on a rebuild.
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0

# SPA-фолбэк, чтобы роутинг работал при F5
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/40-gen-app-config.sh /docker-entrypoint.d/40-gen-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-gen-app-config.sh \
    && MNEMA_FEATURE_AI_ENABLED=false /docker-entrypoint.d/40-gen-app-config.sh \
    && nginx -t \
    && rm -f /usr/share/nginx/html/app-config.js /etc/nginx/conf.d/ai-route.inc

# Копируем стандартный выход Angular 18: dist/<projectName>
COPY --from=build /app/dist/mnema-frontend /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
