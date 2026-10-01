FROM gradle:8.14.5-jdk21@sha256:94452354d9218922457d82e85a343391bab351e7f518f6f5ab1db996967d238b AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY services ./services
RUN chmod +x gradlew
RUN ./gradlew :services:identity-account:bootJar :services:learning:bootJar --no-daemon -x test

FROM eclipse-temurin:21.0.12_8-jre-resolute@sha256:097b5c0e8b5c9cc402e871a87a35f20e9413af9159410db2b1bdd8b78dcca7ed AS backend-runtime
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
