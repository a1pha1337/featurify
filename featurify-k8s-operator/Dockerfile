FROM eclipse-temurin:25-jre
WORKDIR /opt/operator
COPY build/install/featurify-k8s-operator/ ./
USER 10001:10001
ENTRYPOINT ["/opt/operator/bin/featurify-k8s-operator"]
