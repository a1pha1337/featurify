#!/bin/sh
set -eu
test -f /run/featurify-ready
docker info >/dev/null 2>&1
kubectl --request-timeout=5s get --raw=/readyz >/dev/null
