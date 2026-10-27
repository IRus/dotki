#!/bin/sh
set -eu
image=${1:?Usage: smoke-test.sh IMAGE}
container="dotki-smoke-$$"
volume="$container-data"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker volume create "$volume" >/dev/null
docker run -d --name "$container" --network none \
  --read-only --tmpfs /tmp:size=64m \
  --cap-drop ALL --security-opt no-new-privileges:true \
  --mount "type=volume,source=$volume,target=/data" \
  "$image" >/dev/null

wait_for_health() {
  attempt=0
  until docker exec "$container" curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      docker logs "$container"
      exit 1
    fi
    sleep 1
  done
}
wait_for_health
for path in / /client.js /style.css /favicon.svg; do
  docker exec "$container" curl -fsS -o /dev/null "http://127.0.0.1:8080$path"
done
[ "$(docker exec "$container" sqlite3 /data/dotki.sqlite 'PRAGMA integrity_check;')" = ok ]
[ "$(docker exec "$container" sqlite3 /data/dotki.sqlite "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('sessions','events');")" = 2 ]
# This isolated volume is removed by the trap; never use a deployment volume here.
docker exec "$container" sqlite3 /data/dotki.sqlite \
  "CREATE TABLE bootstrap_smoke (value TEXT NOT NULL); INSERT INTO bootstrap_smoke VALUES ('persisted');"
docker restart "$container" >/dev/null
wait_for_health
[ "$(docker exec "$container" sqlite3 /data/dotki.sqlite 'SELECT value FROM bootstrap_smoke;')" = persisted ]
printf 'Health, web assets, SQLite schema and persistence checks passed.\n'
