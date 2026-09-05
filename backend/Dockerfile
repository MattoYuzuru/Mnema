# backend/Dockerfile

FROM gradle:8.14.5-jdk21@sha256:94452354d9218922457d82e85a343391bab351e7f518f6f5ab1db996967d238b AS build
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

FROM eclipse-temurin:21.0.12_8-jre-resolute@sha256:097b5c0e8b5c9cc402e871a87a35f20e9413af9159410db2b1bdd8b78dcca7ed AS backend-runtime
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
