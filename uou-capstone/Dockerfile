FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/app.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
