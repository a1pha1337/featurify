#!/usr/bin/env bash
set -euo pipefail
kubectl -n applications rollout status statefulset/postgres --timeout=60s
for deployment in keycloak featurify featurify-k8s-operator; do
    kubectl -n applications rollout status "deployment/$deployment" --timeout=60s
done
kubectl -n applications wait --for=condition=Ready ffns/checkout --timeout=120s
kubectl -n applications get ffns checkout -o json | jq -e '.status.observedGeneration == .metadata.generation' >/dev/null
curl -fsS http://127.0.0.1:8080/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null
curl -fsS http://127.0.0.1:8081/realms/featurify/.well-known/openid-configuration \
    | jq -e --arg issuer "http://localhost:${LOCAL_KEYCLOAK_PORT}/realms/featurify" '.issuer == $issuer' >/dev/null
# Exercise the same local user/client as the UI without printing credentials or JWTs.
token=$(curl -fsS http://127.0.0.1:8081/realms/featurify/protocol/openid-connect/token \
    --data-urlencode grant_type=password --data-urlencode client_id=featurify \
    --data-urlencode client_secret=featurify-secret --data-urlencode username=featurify \
    --data-urlencode password=featurify --data-urlencode 'scope=openid profile email' | jq -er .access_token)
curl -fsS -H "Authorization: Bearer $token" http://127.0.0.1:8080/api/v1/namespaces \
    | jq -e 'any(.[]; .key == "checkout")' >/dev/null
unset token
kubectl -n applications exec postgres-0 -- psql -U featurify -d featurify -c \
    "select n.key as namespace, coalesce(g.key, 'Global') as scope, f.key, f.type
     from feature f join namespace n on n.id = f.namespace_id
     left join feature_group g on g.id = f.group_id where n.key = 'checkout' order by scope, f.key;"
echo 'PASS: Kubernetes services, Keycloak login, REST and operator reconciliation'
