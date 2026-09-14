#!/usr/bin/env bash
set -Eeuo pipefail

readonly cluster=featurify-local
readonly source=/workspace/docker/local-k8s
export DOCKER_HOST=unix:///var/run/docker.sock
export KIND_EXPERIMENTAL_PROVIDER=docker

shutdown() {
    trap - EXIT
    rm -f /run/featurify-ready
    if docker info >/dev/null 2>&1; then
        while read -r node; do
            [[ -z "$node" ]] || docker stop --time 30 "$node" >/dev/null || true
        done < <(docker ps -q --filter "label=io.x-k8s.kind.cluster=$cluster")
    fi
    if [[ -n "${daemon_pid:-}" ]]; then
        kill "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
}
trap shutdown EXIT
trap 'exit 0' TERM INT
trap 'echo "Bootstrap failed; inspect compose logs. Cluster data is retained." >&2' ERR

rm -f /run/featurify-ready
dockerd-entrypoint.sh > /var/log/featurify-dockerd.log 2>&1 &
daemon_pid=$!
for attempt in {1..60}; do
    docker info >/dev/null 2>&1 && break
    sleep 1
done
if ! docker info >/dev/null 2>&1; then
    tail -n 40 /var/log/featurify-dockerd.log
    exit 1
fi

echo "Building Featurify and operator from the mounted checkout..."
docker build --target service -t featurify:kind-local -f "$source/Applications.Dockerfile" /workspace
docker build --target operator -t featurify-k8s-operator:0.0.1-SNAPSHOT -f "$source/Applications.Dockerfile" /workspace
for image in postgres:17-alpine quay.io/keycloak/keycloak:26.7.3; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
done

if kind get clusters 2>/dev/null | grep -qx "$cluster"; then
    echo "Resuming the existing kind cluster..."
    docker start "${cluster}-control-plane" >/dev/null
else
    kind create cluster --name "$cluster" --image kindest/node:v1.37.0 --config "$source/kind.yaml" --wait 60s
fi
kind export kubeconfig --name "$cluster" --kubeconfig "$KUBECONFIG"
# The DinD daemon is local to this container; the outer published port is for the host.
kubectl config set-cluster "kind-$cluster" --server=https://127.0.0.1:6443 >/dev/null
kubectl wait --for=condition=Ready nodes --all --timeout=120s
kubectl config view --raw --flatten | sed "s#https://127.0.0.1:6443#https://127.0.0.1:${LOCAL_KUBE_PORT}#" > /state/kubeconfig
chmod 600 /state/kubeconfig
kind load docker-image --name "$cluster" featurify:kind-local featurify-k8s-operator:0.0.1-SNAPSHOT \
    postgres:17-alpine quay.io/keycloak/keycloak:26.7.3

kubectl create namespace applications --dry-run=client -o yaml | kubectl apply -f -
jq --arg app "http://localhost:${LOCAL_APP_PORT}" '
    .clients[0].redirectUris = [$app + "/login/oauth2/code/keycloak"] |
    .clients[0].webOrigins = [$app] |
    .clients[0].attributes["post.logout.redirect.uris"] = ($app + "/*") |
    .clientScopes = [{name: "featurify.operator", protocol: "openid-connect", attributes: {"include.in.token.scope": "true"}}] |
    .clients += [{clientId: "featurify-k8s-operator", enabled: true, protocol: "openid-connect",
        publicClient: false, secret: "local-operator-secret", serviceAccountsEnabled: true,
        standardFlowEnabled: false, directAccessGrantsEnabled: false, defaultClientScopes: ["featurify.operator"],
        protocolMappers: [{name: "namespace-keys", protocol: "openid-connect", protocolMapper: "oidc-hardcoded-claim-mapper",
            config: {"claim.name": "featurify_namespace_keys", "claim.value": "[\"checkout\",\"catalog\"]",
                "jsonType.label": "JSON", "access.token.claim": "true"}}]}]
' /workspace/docker/keycloak/featurify-realm.json > /run/featurify-realm.json
kubectl -n applications create configmap keycloak-realm --from-file=featurify-realm.json=/run/featurify-realm.json \
    --dry-run=client -o yaml | kubectl apply -f -
envsubst '${LOCAL_APP_PORT} ${LOCAL_KEYCLOAK_PORT}' < "$source/services.yaml" | kubectl apply -f -
kubectl -n applications rollout status statefulset/postgres --timeout=180s
kubectl -n applications rollout status deployment/keycloak --timeout=240s
kubectl -n applications rollout status deployment/featurify --timeout=240s

kubectl apply -f /workspace/featurify-k8s-operator/deploy/crd.yaml
kubectl wait --for=condition=Established crd/featurifynamespaces.featurify.io --timeout=60s
kubectl apply -f /workspace/featurify-k8s-operator/deploy/operator.yaml
kubectl -n applications set env deployment/featurify-k8s-operator FEATURIFY_CLUSTER_ID="$cluster" RECONCILE_INTERVAL_SECONDS=5
# Reload locally built images on subsequent starts as well (the development tags are stable).
kubectl -n applications rollout restart deployment/featurify deployment/featurify-k8s-operator
kubectl -n applications rollout status deployment/featurify --timeout=180s
kubectl -n applications rollout status deployment/featurify-k8s-operator --timeout=180s
if ! kubectl -n applications get configmap featurify-local-initialized >/dev/null 2>&1; then
    kubectl apply --server-side --field-manager=featurify-gitops -f /workspace/featurify-k8s-operator/examples/checkout.yaml
    kubectl -n applications wait --for=condition=Ready ffns/checkout --timeout=120s
    kubectl -n applications create configmap featurify-local-initialized --from-literal=initialized=true
fi
touch /run/featurify-ready
echo "Ready. Featurify: http://localhost:${LOCAL_APP_PORT} (featurify / featurify)"
echo "Keycloak: http://localhost:${LOCAL_KEYCLOAK_PORT} (admin / admin). Host kubeconfig: .local/k8s/kubeconfig"
wait "$daemon_pid"
