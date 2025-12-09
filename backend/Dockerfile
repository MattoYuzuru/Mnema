# backend/Dockerfile

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services/auth/build.gradle.kts services/auth/
COPY services/user/build.gradle.kts services/user/
COPY services/core/build.gradle.kts services/core/
RUN chmod +x gradlew

RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew \
      :services:auth:dependencies \
      :services:user:dependencies \
      :services:core:dependencies \
      --no-daemon

COPY services ./services

RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew \
      :services:auth:bootJar \
      :services:user:bootJar \
      :services:core:bootJar \
      --no-daemon -x test

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
