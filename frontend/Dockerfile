# --- build stage ---
FROM node:18-alpine AS build
WORKDIR /app

# Копируем только package.json и ставим зависимости (создаст lock внутри образа)
COPY package.json ./
RUN npm install --no-audit --fund=false

# Теперь копируем исходники (lock с хоста НЕ попадёт внутрь из-за .dockerignore)
COPY . .
RUN npm run build

# --- serve stage ---
FROM nginx:alpine
COPY --from=build /app/dist/mnema-frontend/browser /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
