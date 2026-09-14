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
COPY featurify-k8s-operator/build.gradle.kts featurify-k8s-operator/build.gradle.kts
COPY featurify-api/src featurify-api/src
COPY featurify-service/src featurify-service/src
COPY featurify-grpc-api/src featurify-grpc-api/src
COPY featurify-k8s-operator/src featurify-k8s-operator/src
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1g \
    -Pvaadin.productionMode=true :featurify-service:bootJar :featurify-k8s-operator:installDist

FROM eclipse-temurin:25-jre-alpine AS service
WORKDIR /app
COPY --from=build /workspace/featurify-service/build/libs/featurify-service-*.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:25-jre-alpine AS operator
WORKDIR /opt/operator
COPY --from=build /workspace/featurify-k8s-operator/build/install/featurify-k8s-operator/ ./
RUN chmod +x bin/featurify-k8s-operator
USER 10001:10001
ENTRYPOINT ["/opt/operator/bin/featurify-k8s-operator"]
