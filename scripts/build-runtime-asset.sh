#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/src/main/assets
rm -f app/src/main/assets/runtime-aarch64.tar.gz

docker pull --platform linux/arm64 alpine:3.21
container_id=$(docker create --platform linux/arm64 alpine:3.21 /bin/sh -lc '
  set -e
  apk add --no-cache bash ca-certificates curl git maven openjdk17-jdk openjdk21-jdk tar unzip zip
  update-ca-certificates
  java -version
  git --version
  mvn -version
  bash --version
  rm -rf /var/cache/apk/* /tmp/* /root/.cache
')

docker start -a "$container_id"
docker export "$container_id" | gzip -6 > app/src/main/assets/runtime-aarch64.tar.gz
docker rm "$container_id"

gzip -t app/src/main/assets/runtime-aarch64.tar.gz
tar -tzf app/src/main/assets/runtime-aarch64.tar.gz | grep -Eq '(^|\./)usr/bin/java$'
tar -tzf app/src/main/assets/runtime-aarch64.tar.gz | grep -Eq '(^|\./)usr/bin/git$'
tar -tzf app/src/main/assets/runtime-aarch64.tar.gz | grep -Eq '(^|\./)usr/bin/mvn$'
tar -tzf app/src/main/assets/runtime-aarch64.tar.gz | grep -Eq '(^|\./)bin/bash$'
test "$(stat -c%s app/src/main/assets/runtime-aarch64.tar.gz)" -gt 50000000
ls -lh app/src/main/assets/runtime-aarch64.tar.gz
