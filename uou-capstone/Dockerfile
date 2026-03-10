FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# 현재 디렉토리(uou-capstone)의 모든 파일 복사
COPY . ./

# 목록 API용 DTO (캐시된 COPY 이후에도 최신 파일이 들어가도록 명시 복사)
COPY src/main/java/io/github/uou_capstone/aiplatform/domain/exam/dto/ExamSessionListItemDto.java \
     /workspace/src/main/java/io/github/uou_capstone/aiplatform/domain/exam/dto/
COPY src/main/java/io/github/uou_capstone/aiplatform/domain/material/dto/MaterialListItemDto.java \
     /workspace/src/main/java/io/github/uou_capstone/aiplatform/domain/material/dto/
COPY src/main/java/io/github/uou_capstone/aiplatform/domain/material/generation/dto/MaterialGenerationSessionSummaryDto.java \
     /workspace/src/main/java/io/github/uou_capstone/aiplatform/domain/material/generation/dto/

RUN chmod +x gradlew \
    && ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

