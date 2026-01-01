# backend/Dockerfile

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

# 1. Копируем только gradle-конфиги для кэширования зависимостей
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services/auth/build.gradle.kts services/auth/
COPY services/user/build.gradle.kts services/user/
COPY services/core/build.gradle.kts services/core/
COPY services/core/build.gradle.kts services/media/
RUN chmod +x gradlew

# 2. Прогреваем зависимости (используя кеш BuildKit)
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew \
      :services:auth:dependencies \
      :services:user:dependencies \
      :services:core:dependencies \
      :services:media:dependencies \
      --no-daemon

# 3. Копируем исходники и собираем все bootJar одним прогоном
COPY services ./services

RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew \
      :services:auth:bootJar \
      :services:user:bootJar \
      :services:core:bootJar \
      :services:media:bootJar \
      --no-daemon -x test

# 4. Runtime-образы

FROM eclipse-temurin:21-jre AS auth-runtime
WORKDIR /app
COPY --from=build /workspace/services/auth/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM eclipse-temurin:21-jre AS user-runtime
WORKDIR /app
COPY --from=build /workspace/services/user/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM eclipse-temurin:21-jre AS core-runtime
WORKDIR /app
COPY --from=build /workspace/services/core/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM eclipse-temurin:21-jre AS media-runtime
WORKDIR /app
COPY --from=build /workspace/services/media/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
