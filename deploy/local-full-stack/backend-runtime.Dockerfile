FROM gradle:9.8.0-jdk25@sha256:7086a4cd10d568b35cafd6d5d30323865f3ce1f23ccddc9570ff3e3c9c8cd6e9 AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services ./services
RUN chmod +x gradlew
RUN ./gradlew :services:identity-account:bootJar :services:learning:bootJar --no-daemon -x test

FROM eclipse-temurin:25.0.4.1_1-jre-resolute@sha256:628f28c18211e8633d02cefb9698489abcbd43337c61fba67837e4ffb86d50d6 AS backend-runtime
RUN rm -f /usr/bin/pebble
WORKDIR /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

FROM backend-runtime AS identity-account-runtime
ENV PORT=8081
COPY --from=build /workspace/services/identity-account/build/libs/*.jar app.jar

FROM backend-runtime AS learning-runtime
COPY --from=build /workspace/services/learning/build/libs/*.jar app.jar

FROM docker:29.5.2-cli@sha256:9ba8e32bfc35a2c7ae2feb1e3241b2778ae21dee80f4dcd31d04e1cfdea86ea2 AS docker-cli

# Local media processor only: the worker gateway shells out to `docker run`. The static
# client binary is copied into this one target; the browser-facing learning-runtime
# has neither the client nor the Docker socket.
FROM learning-runtime AS learning-media-processor-runtime
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
