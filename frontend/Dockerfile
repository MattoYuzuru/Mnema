# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /app

# сначала только манифесты — кэш установки зависимостей
COPY package.json package-lock.json ./
RUN npm ci

# теперь исходники
COPY . .
RUN npm run build

# --- serve stage ---
FROM nginx:alpine
COPY --from=build /app/dist/mnema-frontend/browser /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
