FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY main-service/uou-capstone/ ./

RUN chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

