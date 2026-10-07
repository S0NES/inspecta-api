#!/bin/bash
# Build-runner do inspecta-api (chamado via docker compose pelo job Jenkins "test" com
# MODULE=inspecta-api). Faz build + testes:
#   [1/4] mvn verify (unit + integracao, incl. Testcontainers)
#   [2/4] docker build (deixa a imagem final tagueada no daemon do host)
#   [3/4] sobe um stack efemero isolado (Postgres + RabbitMQ + app)
#   [4/4] smoke de API (Newman) contra esse stack
# NAO publica imagem nem aciona deploy -- isso e responsabilidade dos jobs
# "upload/upload-api" e "deploy/deploy-api" no Jenkins.
set -euo pipefail

RUN_ID="api-$(date +%s)-$$"
NET="ci-net-${RUN_ID}"
PG="ci-pg-${RUN_ID}"
MQ="ci-mq-${RUN_ID}"
APP="ci-app-${RUN_ID}"
SELF="$(hostname)"
REGISTRY="${REGISTRY:-registry.inspecta.viniciusscarabelli.tech}"
IMAGE_NAME="${IMAGE_NAME:-${REGISTRY}/inspecta-api}"
FINAL_TAG="${IMAGE_NAME}:${IMAGE_TAG:-latest}"
IMAGE="${IMAGE_NAME}:ci-${RUN_ID}"

cleanup() {
  echo "== cleanup =="
  docker rm -f "$PG" "$MQ" "$APP" >/dev/null 2>&1 || true
  docker network disconnect "$NET" "$SELF" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "== [1/4] mvn verify (testes existentes, incl. Testcontainers — docker.sock disponível aqui) =="
mvn -B -s settings.xml -f pom.xml verify

echo "== [2/4] docker build (tag efêmera + tag final; publish/deploy ficam a cargo do Jenkins) =="
# Cache-bust da layer `mvn package` do Dockerfile: o inspecta-core e SNAPSHOT
# (mutavel), e sem isto o Docker serve o jar do build anterior quando pom.xml/src
# nao mudaram. O <lastUpdated> do maven-metadata do SNAPSHOT muda a cada deploy do
# inspecta-core; se nao resolver (offline / versao fixa), cai pro epoch e builda limpo.
GU_VER=$(sed -n '/<artifactId>inspecta-core<\/artifactId>/,/<\/dependency>/ s:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
GU_TS=$(curl -sf "https://maven.inspecta.viniciusscarabelli.tech/snapshots/com/inspecta/inspecta-core/${GU_VER}/maven-metadata.xml" \
  | sed -n 's:.*<lastUpdated>\(.*\)</lastUpdated>.*:\1:p' | head -1 || true)
docker build --build-arg "GU_SNAPSHOT_TS=${GU_TS:-$(date +%s)}" -t "$IMAGE" -f Dockerfile .
docker tag "$IMAGE" "$FINAL_TAG"

echo "== [3/4] stack efêmero isolado (rede só deste run) para testes de API =="
docker network create "$NET" >/dev/null
docker network connect "$NET" "$SELF"

docker run -d --name "$PG" --network "$NET" \
  -e POSTGRES_DB=inspecta -e POSTGRES_USER=inspecta -e POSTGRES_PASSWORD=inspecta \
  postgres:16-alpine >/dev/null
docker run -d --name "$MQ" --network "$NET" \
  -e RABBITMQ_DEFAULT_USER=inspecta -e RABBITMQ_DEFAULT_PASS=inspecta \
  rabbitmq:3.13-management-alpine >/dev/null

echo "aguardando postgres..."
for i in $(seq 1 30); do
  docker exec "$PG" pg_isready -U inspecta >/dev/null 2>&1 && break
  sleep 2
done

docker run -d --name "$APP" --network "$NET" \
  -e SPRING_PROFILES_ACTIVE=ci \
  -e DATABASE_URL="jdbc:postgresql://${PG}:5432/inspecta" \
  -e DATABASE_USER=inspecta -e DATABASE_PASSWORD=inspecta \
  -e RABBITMQ_HOST="$MQ" -e RABBITMQ_USER=inspecta -e RABBITMQ_PASSWORD=inspecta \
  -e JWT_SECRET="${CI_JWT_SECRET:-ci-only-secret-nao-usado-em-prod-0000000000}" \
  "$IMAGE" >/dev/null

echo "aguardando /actuator/health..."
OK=""
for i in $(seq 1 40); do
  if curl -fsS "http://${APP}:8080/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    OK=1; break
  fi
  sleep 3
done
if [ -z "$OK" ]; then
  echo "inspecta-api não ficou saudável a tempo — logs:"; docker logs "$APP" || true; exit 1
fi

echo "== [4/4] testes de API (Newman, collection local ci/postman/ci-smoke.postman_collection.json) =="
newman run ci/postman/ci-smoke.postman_collection.json \
  --env-var "baseUrl=http://${APP}:8080" \
  --env-var "userEmail=ci-admin@inspecta.test" \
  --env-var "userSenha=ci-test-only-9f3a"

echo "== inspecta-api: mvn verify + docker build + smoke de API OK =="
