FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :services:user:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/user/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
