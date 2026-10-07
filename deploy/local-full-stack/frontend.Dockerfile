FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --fund=false
COPY frontend/ ./
ARG MNEMA_FRONTEND_CONFIGURATION=production
RUN case "$MNEMA_FRONTEND_CONFIGURATION" in production|development) ;; *) exit 2 ;; esac \
    && npx --no-install ng build --configuration "$MNEMA_FRONTEND_CONFIGURATION"

FROM nginx:1.31.6-alpine@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2
RUN apk add --no-cache --upgrade \
    'libcrypto3>=3.5.8-r0' \
    'libssl3>=3.5.8-r0' \
    'openssl>=3.5.8-r0'

COPY deploy/local-full-stack/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/docker/40-gen-app-config.sh /docker-entrypoint.d/40-gen-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-gen-app-config.sh \
    && mkdir -p /run/secrets \
    && openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 -subj '/CN=build-check' \
      -keyout /run/secrets/mnema_local_tls_key -out /run/secrets/mnema_local_tls_cert >/dev/null 2>&1 \
    && /docker-entrypoint.d/40-gen-app-config.sh \
    && nginx -t \
    && rm -rf /run/secrets \
    && rm -f /usr/share/nginx/html/app-config.js
COPY --from=build /app/dist/mnema-frontend /usr/share/nginx/html

EXPOSE 8443 8444
CMD ["nginx", "-g", "daemon off;"]
