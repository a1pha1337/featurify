FROM docker:29.2.1-dind
ARG TARGETARCH
ARG KIND_VERSION=v0.33.0
ARG KUBECTL_VERSION=v1.37.0
RUN apk add --no-cache bash curl gettext jq \
    && curl -fsSL "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-linux-${TARGETARCH}" -o /usr/local/bin/kind \
    && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kind /usr/local/bin/kubectl
COPY *.sh /lab/
RUN sed -i 's/\r$//' /lab/*.sh && chmod +x /lab/*.sh
ENTRYPOINT ["/lab/start.sh"]
