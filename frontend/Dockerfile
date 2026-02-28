# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /app

# Берём только package.json, NPM сам создаст lock внутри образа
COPY package.json ./
RUN npm install --no-audit --fund=false

# Теперь исходники
COPY . .

# Сборка Angular (без кастомного output-path)
RUN npx -y -p @angular/cli@18 ng build --configuration production

# --- serve stage ---
FROM nginx:alpine

# SPA-фолбэк, чтобы роутинг работал при F5
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/40-gen-app-config.sh /docker-entrypoint.d/40-gen-app-config.sh
RUN chmod +x /docker-entrypoint.d/40-gen-app-config.sh

# Копируем стандартный выход Angular 18: dist/<projectName>
COPY --from=build /app/dist/mnema-frontend /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
