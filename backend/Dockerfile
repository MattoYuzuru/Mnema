# backend/Dockerfile

FROM gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9 AS build
WORKDIR /workspace

# The root build still verifies non-shipping modules until their owning epics
# remove them. Only these two bootJar tasks contribute to release images.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services ./services
RUN chmod +x gradlew

RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew \
      :services:identity-account:bootJar \
      :services:learning:bootJar \
      --no-daemon -x test

FROM eclipse-temurin:25.0.4.1_1-jre-resolute@sha256:628f28c18211e8633d02cefb9698489abcbd43337c61fba67837e4ffb86d50d6 AS backend-runtime
RUN rm -f /usr/bin/pebble
WORKDIR /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM backend-runtime AS identity-account-runtime
ENV PORT=8080
COPY --from=build /workspace/services/identity-account/build/libs/*.jar app.jar

FROM backend-runtime AS learning-runtime
COPY --from=build /workspace/services/learning/build/libs/*.jar app.jar
