FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle.properties ./
COPY featurify-api/build.gradle.kts featurify-api/build.gradle.kts
COPY featurify-service/build.gradle.kts featurify-service/build.gradle.kts
COPY featurify-grpc-starter/build.gradle.kts featurify-grpc-starter/build.gradle.kts
COPY featurify-grpc-api/build.gradle.kts featurify-grpc-api/build.gradle.kts
COPY featurify-grpc-client/build.gradle.kts featurify-grpc-client/build.gradle.kts
RUN chmod +x gradlew
RUN ./gradlew --no-daemon --quiet :featurify-service:dependencies
COPY featurify-api/src featurify-api/src
COPY featurify-grpc-api/src featurify-grpc-api/src
COPY featurify-service/src featurify-service/src
RUN ./gradlew --no-daemon -Pvaadin.productionMode=true :featurify-service:bootJar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S featurify && adduser -S featurify -G featurify
WORKDIR /app
COPY --from=build /workspace/featurify-service/build/libs/featurify-service-*.jar app.jar
USER featurify
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
