# --- build stage ---
FROM node:22.23.2-alpine AS build
WORKDIR /app

# Dependency resolution must match the tested lockfile exactly.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --fund=false

# Теперь исходники
COPY . .

# Use the repository-local Angular CLI selected by package-lock.json.
RUN npm run build

# --- serve stage ---
FROM nginx:alpine

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
