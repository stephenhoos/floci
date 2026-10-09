#!/usr/bin/env bash
set -euo pipefail
image="${1:?Provide the built image}"
name="floci-release-smoke"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" -p 127.0.0.1:15666:4566 \
  -e FLOCI_SERVICES_UI_ENABLED=false -e FLOCI_TLS_ENABLED=false \
  -e FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED=false "$image"
ready=false
for _ in {1..120}; do
    if curl -fsS http://127.0.0.1:15666/_floci/health >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 2
done
if [ "$ready" != true ]; then
    docker logs "$name"
    exit 1
fi
docker top "$name" -eo uid,pid,comm | grep -E '^ *1001 +[0-9]+ +java$'
status="$(curl -s -o /dev/null -w '%{http_code}' \
  -H 'Origin: https://attacker.example' http://127.0.0.1:15666/)"
test "$status" = 403
docker exec "$name" awslocal s3api create-bucket --bucket floci-release-smoke
docker exec "$name" sh -c 'printf "release smoke test" >/tmp/smoke.txt'
docker exec "$name" awslocal s3api put-object --bucket floci-release-smoke --key test --body /tmp/smoke.txt
docker exec "$name" awslocal s3api get-object --bucket floci-release-smoke --key test /tmp/received.txt
docker exec "$name" sh -c 'test "$(cat /tmp/received.txt)" = "release smoke test"'
docker exec "$name" awslocal s3api delete-object --bucket floci-release-smoke --key test
docker exec "$name" awslocal s3api delete-bucket --bucket floci-release-smoke
