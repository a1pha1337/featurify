FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew
RUN ./gradlew --no-daemon --quiet dependencies
COPY src src
RUN ./gradlew --no-daemon -Pvaadin.productionMode=true clean bootJar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S featurify && adduser -S featurify -G featurify
WORKDIR /app
COPY --from=build /workspace/build/libs/featurify-*.jar app.jar
USER featurify
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
