FROM node:22.23.2-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --fund=false
COPY frontend/ ./
RUN npm run build

FROM nginx:1.31.4-alpine@sha256:db35bfc6b2951e7f8a72db5db120288c127ffaeeb4a6d4b95a26fead017d5913
RUN apk add --no-cache --upgrade \
    libcrypto3=3.5.8-r0 \
    libssl3=3.5.8-r0 \
    openssl=3.5.8-r0

COPY deploy/local-full-stack/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/docker/40-gen-app-config.sh /docker-entrypoint.d/40-gen-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-gen-app-config.sh \
    && mkdir -p /run/secrets \
    && openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 -subj '/CN=build-check' \
      -keyout /run/secrets/mnema_local_tls_key -out /run/secrets/mnema_local_tls_cert >/dev/null 2>&1 \
    && MNEMA_FEATURE_AI_ENABLED=false /docker-entrypoint.d/40-gen-app-config.sh \
    && nginx -t \
    && rm -rf /run/secrets \
    && rm -f /usr/share/nginx/html/app-config.js /etc/nginx/conf.d/ai-route.inc
COPY --from=build /app/dist/mnema-frontend /usr/share/nginx/html

EXPOSE 8443 8444
CMD ["nginx", "-g", "daemon off;"]
