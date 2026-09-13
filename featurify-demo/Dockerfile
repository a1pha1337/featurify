FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY featurify-api/build.gradle.kts featurify-api/build.gradle.kts
COPY featurify-service/build.gradle.kts featurify-service/build.gradle.kts
COPY featurify-grpc-api/build.gradle.kts featurify-grpc-api/build.gradle.kts
COPY featurify-grpc-client/build.gradle.kts featurify-grpc-client/build.gradle.kts
COPY featurify-grpc-starter/build.gradle.kts featurify-grpc-starter/build.gradle.kts
COPY featurify-demo/build.gradle.kts featurify-demo/build.gradle.kts
RUN chmod +x gradlew
COPY featurify-grpc-api/src featurify-grpc-api/src
COPY featurify-grpc-client/src featurify-grpc-client/src
COPY featurify-grpc-starter/src featurify-grpc-starter/src
COPY featurify-demo/src featurify-demo/src
RUN ./gradlew --no-daemon :featurify-demo:bootJar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S featurify && adduser -S featurify -G featurify
WORKDIR /app
COPY --from=build /workspace/featurify-demo/build/libs/featurify-demo-*.jar app.jar
USER featurify
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
