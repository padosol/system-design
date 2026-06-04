# 사전 빌드된 Spring Boot jar를 담는 런타임 이미지.
# 빌드 전 `./gradlew bootJar` 필요.
FROM eclipse-temurin:21-jre
WORKDIR /app
ARG JAR=build/libs/url-shortener-0.0.1-SNAPSHOT.jar
COPY ${JAR} app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms512m", "-Xmx512m", "-jar", "app.jar"]
