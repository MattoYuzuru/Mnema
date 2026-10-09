# --- build stage ---
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build
WORKDIR /app

# Dependency resolution must match the tested lockfile exactly.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --fund=false

# Теперь исходники
COPY . .

# Use the repository-local Angular CLI selected by package-lock.json.
RUN npm run build

# --- serve stage ---
FROM nginx:1.31.6-alpine@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2

# Retain the reviewed nginx base with the first fixed Alpine OpenSSL/Expat/PCRE2/TIFF builds.
# A floor (not an exact pin) keeps rebuilds working when Alpine ships a newer security patch and
# drops the old one; the release image scan stays the gate for what is actually installed.
RUN apk add --no-cache --upgrade \
    'libcrypto3>=3.5.8-r0' \
    'libssl3>=3.5.8-r0' \
    'libexpat>=2.8.5-r0' \
    'pcre2>=10.49-r0' \
    'tiff>=4.7.2-r0'

# SPA-фолбэк, чтобы роутинг работал при F5
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/40-gen-app-config.sh /docker-entrypoint.d/40-gen-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-gen-app-config.sh \
    && /docker-entrypoint.d/40-gen-app-config.sh \
    && nginx -t \
    && rm -f /usr/share/nginx/html/app-config.js

# Копируем выход Angular application builder: dist/<projectName>
COPY --from=build /app/dist/mnema-frontend /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
