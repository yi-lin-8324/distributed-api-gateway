# Distributed API Gateway & Rate-Limiting Platform
[![Gateway CI](https://github.com/yi-lin-8324/distributed-api-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/yi-lin-8324/distributed-api-gateway/actions/workflows/ci.yml)

A Java 21 / Spring Boot 4 gateway that authenticates API keys, forwards `GET /proxy/**` requests to a configurable upstream, and enforces **shared per-user and per-tenant rate limits** using Redis and an atomic Lua script.

The project is a focused demonstration of distributed rate limiting, caching, resilience, observability, and containerized deployment. It is **not** intended to be a general-purpose API gateway.

## Features

- **API key authentication:** SHA-256 lookup of active API-key records in PostgreSQL; associates requests with a user and tenant. Raw API keys are not stored in the database or written to audit logs.
- **Distributed quotas:** Separate user and tenant token buckets in shared Redis. An atomic Lua script admits a request only when **both** buckets have tokens, and rejected requests do not consume tokens.
- **Rate-limit responses:** Returns HTTP `429` when quotas are exhausted; includes `Retry-After` when a retry time can be calculated.
- **Fail closed:** Returns HTTP `503` rather than bypassing quotas when a required Redis operation fails.
- **Two-level caching:** Caffeine L1 (30-second TTL) and Redis L2 (60-second TTL) for API-key identity and quota-policy lookups. PostgreSQL remains the source of truth.
- **Upstream resilience:** Resilience4j circuit breaker around outbound HTTP calls, plus controlled responses for upstream connection failures.
- **Observability:** Structured JSON audit logs, Spring Boot Actuator / Prometheus metrics, a Grafana dashboard, and OpenTelemetry traces viewable in Jaeger.
- **Deployments and testing:** Docker image, Docker Compose, local Kubernetes Deployment/Service with health probes, JUnit/Testcontainers integration tests, and k6 load tests.

## Architecture

```text
Client
  |
  | GET /proxy/** + X-API-Key
  v
Spring Boot Gateway (one or more instances)
  |-- API-key lookup: Caffeine L1 -> Redis L2 -> PostgreSQL
  |-- Policy lookup:  Caffeine L1 -> Redis L2 -> PostgreSQL
  |-- Rate limit:     atomic Lua -> shared Redis user + tenant buckets
  |-- Circuit breaker + RestClient
  v
Configurable HTTP upstream

Gateway -- metrics --> Prometheus --> Grafana
Gateway -- OTLP traces ----------------> Jaeger
Gateway -- structured JSON logs ------> console
```

Two gateway instances share the same Redis counters; quotas are **not** maintained independently per instance.

## Technology

Java 21, Spring Boot 4.1.1 (Spring MVC and RestClient), PostgreSQL 17, Redis 7, Caffeine, Resilience4j, Spring Boot Actuator, Micrometer/Prometheus, OpenTelemetry/Jaeger, Grafana, Docker Compose, Kubernetes, JUnit, Testcontainers, and k6.

## HTTP behavior

| Situation | Expected response |
|---|---|
| Missing, unknown, or inactive API key | `401 Unauthorized` |
| Authenticated request with available user and tenant tokens | Upstream response, e.g. `200 OK` |
| User **or** tenant quota exhausted | `429 Too Many Requests` |
| Redis required for rate limiting is unavailable | `503 Service Unavailable` (fail closed) |
| Upstream connection error | `502 Bad Gateway` |
| Upstream circuit breaker is open | `503 Service Unavailable` |
| Upstream returns HTTP `404` or `5xx` | Upstream response is propagated when the call is permitted |

Only the scoped `GET /proxy/**` forwarding route is implemented. This project does not implement OAuth, Kafka, an administrative API, or a general routing framework.

## Prerequisites

- JDK 21 (for building locally)
- Docker Desktop and Docker Compose
- Maven wrapper (`./mvnw`, included in the project)
- Python 3 (for the local mock upstream)
- `kubectl` and an enabled Docker Desktop Kubernetes cluster (for Kubernetes steps)
- Homebrew / k6 (for load tests on macOS)

## Quick start: Docker Compose

These steps use the project's existing `Dockerfile` and `compose.yaml`. The Compose setup contains `gateway`, `gateway-2`, `postgres`, and `redis`; the gateway uses `host.docker.internal` to reach the mock upstream on your Mac. The second gateway is useful for distributed-quota testing but is not required for the first request.

### 1. Start a local upstream

From the project root:

```bash
mkdir -p mock-upstream
printf 'Hello from upstream!\n' > mock-upstream/hello
python3 -m http.server 8081 --directory mock-upstream
```

Keep this process running in its own terminal. It serves `http://localhost:8081/hello`.

### 2. Build the gateway image

In another terminal:

```bash
./mvnw -DskipTests package
docker build -t gateway:local .
```

The image runs the packaged Spring Boot JAR with Java 21.

### 3. Start PostgreSQL and Redis

For this **local development example**, set the database password in your shell instead of checking it into Git:

```bash
export GATEWAY_DB_PASSWORD=devpassword
docker compose up -d postgres redis
```

Verify PostgreSQL and Redis:

```bash
docker compose exec postgres pg_isready -U gateway -d gateway
docker compose exec redis redis-cli ping
```

Expected: PostgreSQL accepts connections, and Redis returns `PONG`.

### 4. Initialize the database and create a test API key

Generate a new raw API key and hash it locally:

```bash
export API_KEY="$(openssl rand -hex 32)"
API_KEY_HASH="$(printf '%s' "$API_KEY" | shasum -a 256 | awk '{print $1}')"
```

Create the two tables and seed a demonstration identity and its policies:

```bash
docker compose exec -T postgres \
  psql -X -v ON_ERROR_STOP=1 -U gateway -d gateway \
  -v key_hash="$API_KEY_HASH" <<'SQL'
CREATE TABLE IF NOT EXISTS api_keys (
    key_hash TEXT PRIMARY KEY,
    active BOOLEAN NOT NULL,
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS quota_policies (
    policy_key TEXT PRIMARY KEY,
    capacity INTEGER NOT NULL,
    refill_rate DOUBLE PRECISION NOT NULL
);

INSERT INTO api_keys (key_hash, active, user_id, tenant_id)
VALUES (:'key_hash', true, 'user_1', 'tenant_A')
ON CONFLICT (key_hash) DO UPDATE SET active = true;

INSERT INTO quota_policies (policy_key, capacity, refill_rate)
VALUES
    ('user:tenant_A:user_1', 3, 1.0),
    ('tenant:tenant_A', 5, 1.0)
ON CONFLICT (policy_key) DO UPDATE
SET capacity = EXCLUDED.capacity,
    refill_rate = EXCLUDED.refill_rate;
SQL
```

Keep `API_KEY` in your shell for the following `curl` and k6 commands. Do not commit it to the repository or share it in logs or screenshots.

### 5. Start the gateway

```bash
docker compose up -d gateway
# Optional second instance:
docker compose up -d gateway-2
docker compose ps
```

The original gateway is exposed at `http://localhost:8080`, and the second instance at `http://localhost:8084`.

### 6. Verify the API

Without a key:

```bash
curl -i http://localhost:8080/proxy/hello
# Expected: 401
```

With the key created above:

```bash
curl -i -H "X-API-Key: $API_KEY" http://localhost:8080/proxy/hello
# Expected while tokens are available: 200, Hello from upstream!
```

After the quota is exhausted, the gateway returns `429`. To investigate startup issues, run `docker compose logs --tail=80 gateway`.

## Configuration

The gateway reads its existing Spring Boot properties and environment-variable overrides. Important Compose settings are:

| Setting | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/gateway` | Compose PostgreSQL address |
| `SPRING_DATASOURCE_USERNAME=gateway` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | Database password; supplied from `GATEWAY_DB_PASSWORD` |
| `SPRING_DATA_REDIS_HOST=redis` | Compose Redis service name |
| `UPSTREAM_BASEURL=http://host.docker.internal:8081` | Mock upstream on the Mac host |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://host.docker.internal:4318/v1/traces` | Local Jaeger OTLP HTTP endpoint |

Inside a Docker container, `localhost` refers to that container. Compose services reach one another by name, such as `postgres:5432` and `redis:6379`. The `host.docker.internal` address refers to services published on the Docker Desktop host.

## Rate limiting and caching

Each admitted request consumes one token from both the user and tenant buckets. The Lua script runs atomically in Redis, preventing concurrent gateway instances from consuming the same token twice. Tokens refill according to the configured policy; a request is rejected if either bucket lacks sufficient tokens.

The keys follow these patterns:

```text
bucket:user:<tenant_id>:<user_id>
bucket:tenant:<tenant_id>
```

Policies are stored in PostgreSQL as:

```text
user:<tenant_id>:<user_id>
tenant:<tenant_id>
```

Caffeine L1 caches identity and policy data for 30 seconds, and Redis L2 caches it for 60 seconds. This reduces database queries but means API-key revocations and policy updates can remain **temporarily stale** until cached entries expire. The Redis token buckets remain shared and authoritative for live quota consumption.

The circuit breaker is configured for local demonstration with a small sliding window: 50% failure threshold, a two-call minimum/window, and a 30-second open-state wait. These settings are not claimed to be production-tuned.

## Health and observability

### Health endpoints

```bash
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/prometheus
```

The first two report Spring Boot's application availability state. The default readiness group does **not** automatically guarantee PostgreSQL or Redis connectivity.

### Logs

Structured JSON audit events include authentication rejection, rate-limit rejection, request admission, and upstream response status. Events use user/tenant identifiers where appropriate; raw API keys and key hashes are not logged. A `request_admitted` event means the quota check succeeded, **not** that the upstream ultimately returned `200`.

### Prometheus and Grafana

The project includes `observability/prometheus.yml`, configured to scrape `http://host.docker.internal:8080/actuator/prometheus` every 5 seconds. For a fresh local setup, start Prometheus and Grafana as separate Docker containers:

```bash
docker run -d --name gateway-prometheus \
  -p 127.0.0.1:9090:9090 \
  -v "$(pwd)/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  prom/prometheus:v3.5.5

docker volume create gateway-grafana-data
docker run -d --name gateway-grafana \
  -p 127.0.0.1:3000:3000 \
  -v gateway-grafana-data:/var/lib/grafana \
  grafana/grafana

docker network create gateway-observability
docker network connect gateway-observability gateway-prometheus
docker network connect gateway-observability gateway-grafana
```

The network commands are one-time setup steps. If the containers or network already exist, reuse them rather than recreating them. In Grafana, add a Prometheus data source using `http://gateway-prometheus:9090` and save a time-series dashboard panel with the PromQL query below.

The verified local setup uses:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Grafana data source: `http://gateway-prometheus:9090` on a shared Docker network

Example PromQL:

```promql
http_server_requests_seconds_count{job="gateway",status="401"}
```

A Grafana dashboard named **Gateway Monitoring** was created with an **HTTP 401 Responses (Cumulative)** time-series panel. This is a counter; it measures a cumulative total, not requests per second.

### OpenTelemetry and Jaeger

The gateway exports OpenTelemetry traces over OTLP HTTP to the local Jaeger receiver (`localhost:4318` when run directly, or `host.docker.internal:4318` from the container). Start Jaeger if not already running:

```bash
docker run -d --name gateway-jaeger \
  -p 127.0.0.1:16686:16686 \
  -p 127.0.0.1:4318:4318 \
  cr.jaegertracing.io/jaegertracing/jaeger:2.22.0
```

Jaeger's UI is available at `http://localhost:16686`. Restart the gateway after starting the receiver if needed.

Verified traces contain an incoming gateway HTTP span and an outgoing `RestClient` HTTP client span for the upstream request. The Python mock upstream is **not instrumented**, so its internal work is not visible as a separate server-side trace. Sampling is set to `1.0` (100%) for local verification.

The Prometheus, Grafana, and Jaeger instances were started as **separate Docker containers**, not as part of the current gateway Compose file.

## Local Kubernetes deployment

The repository includes:

```text
k8s/gateway-deployment.yaml
k8s/gateway-service.yaml
```

The Deployment maintains **one replica** of the `gateway:local` image, uses `imagePullPolicy: Never`, and reads the development PostgreSQL password from a Kubernetes Secret. It defines:

- Readiness probe: `GET /actuator/health/readiness`
- Liveness probe: `GET /actuator/health/liveness`
- A `ClusterIP` Service selecting Pods labeled `app: gateway`

On Docker Desktop, select the `docker-desktop` Kubernetes context and verify the node is Ready:

```bash
kubectl config use-context docker-desktop
kubectl get nodes
```

Create the development Secret (once per namespace), then deploy:

```bash
kubectl create secret generic gateway-db \
  --from-literal="password=${GATEWAY_DB_PASSWORD:?Set GATEWAY_DB_PASSWORD}"
kubectl apply -f k8s/gateway-deployment.yaml
kubectl apply -f k8s/gateway-service.yaml
kubectl rollout status deployment/gateway --timeout=120s
kubectl get pods -l app=gateway
```

In the **verified local Kubernetes configuration**, the Deployment references the **original standalone** PostgreSQL instance published on host port `5433` and Redis on host port `6379`, via `host.docker.internal`. These are **not** the Compose-only `postgres` and `redis` DNS names. Make sure those original instances are available, populated, and accessible before deploying. Kubernetes and Compose use separate networks.

Forward the Kubernetes Service to a free local port (different from the Compose gateway):

```bash
kubectl port-forward service/gateway 8083:8080
```

In a second terminal:

```bash
curl -i http://localhost:8083/proxy/hello
# Use a key stored in the ORIGINAL standalone PostgreSQL database:
curl -i -H "X-API-Key: $K8S_API_KEY" http://localhost:8083/proxy/hello
```

`K8S_API_KEY` must be an active key present in the standalone PostgreSQL database used by this Kubernetes manifest. A key created only in the Compose PostgreSQL database will not authenticate here unless you seed it in the standalone database too.

The verified deployment reported a successful rollout, a `1/1 Running` Pod, functional HTTP forwarding, and configured readiness/liveness probes.

## Automated testing

Run the full suite:

```bash
./mvnw test
```

Docker Desktop must be running because integration tests use Testcontainers. The complete Maven suite was run successfully (`BUILD SUCCESS`); an aggregate test count is not recorded here.

| Test area | Verified coverage |
|---|---|
| Gateway controller | Authentication/status responses, rate-limit rejection, Redis failure behavior, and upstream circuit-breaker handling |
| `PostgresContainerTest` | PostgreSQL connectivity, active key lookup, inactive key rejection |
| `RedisContainerTest` | Redis connectivity, Lua script loading, admitted request, exhausted user quota, no token consumption on rejection, shared tenant quota |
| `GatewayHttpIntegrationTest` | Missing key `401`, inactive key `401`, authenticated forwarding and response body `200`, exhausted quota `429` |

The HTTP integration test uses temporary PostgreSQL and Redis containers plus a JUnit-managed upstream HTTP stub. Tests do not need the developer's manually started PostgreSQL, Redis, or Python upstream services.

## Load testing and distributed evidence

Install k6 on macOS with `brew install k6`.

```bash
# Five unauthenticated requests; expected 401 checks
k6 run load-tests/smoke.js

# Twenty authenticated requests; expected statuses are 200 or 429
k6 run -e API_KEY="$API_KEY" load-tests/authenticated.js

# Fifty total requests across ten virtual users
k6 run --vus 10 --iterations 50 \
  -e API_KEY="$API_KEY" load-tests/authenticated.js
```

`429` is an expected rate-limit result in these tests; k6's default `http_req_failed` metric may still classify that HTTP status as a failure. Review the explicit response checks as well as `http_reqs` and `http_req_duration`.

**Verified evidence:**

| Scenario | Result |
|---|---|
| k6 unauthenticated smoke test | 5 checks passed |
| k6 authenticated traffic | 20 checks passed (200 or 429 allowed) |
| k6 concurrent traffic | 50 checks passed using 10 VUs |
| Two gateways sharing Redis, sequential requests, one-token quota | Gateway 1: `200`; Gateway 2: `429` |
| Two gateways sharing Redis, concurrently launched requests, one-token quota | Gateway 1: `200`; Gateway 2: `429` |
| Full Maven regression run | `BUILD SUCCESS` |

The two-instance checks used a fresh API-key identity and user/tenant policies with capacity `1` and refill rate `0`. The concurrent check establishes one admission and one rejection for that test, but it is **not** a throughput or production-scale benchmark. No latency percentile, maximum RPS, or performance improvement claim is made because those measurements were not recorded.


### Local k6 Benchmark Results

Command:

```bash
k6 run --vus 10 --iterations 50 \
  -e API_KEY="$API_KEY" \
  load-tests/authenticated.js
```

| Metric | Result |
|---|---|
| Virtual users | 10 |
| Total requests | 50 |
| Throughput | 40.00 requests/second |
| Average response time | 32 ms |
| Median response time | 18 ms |
| 95th percentile response time | 85 ms |
| Maximum response time | 110 ms |
| Checks passed | 50/50 (100%) |
| Interrupted iterations | 0 |

The test accepted HTTP 200 and HTTP 429 as expected responses.

These numbers represent a short local development test
including rate-limited requests. They are not measurements
of maximum production throughput or successful-request
throughput.


## Project layout

```text
.
├── Dockerfile
├── compose.yaml
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/gateway/
│   │   └── resources/
│   │       ├── application.properties
│   │       └── token_bucket.lua
│   └── test/java/com/example/gateway/
├── k8s/
│   ├── gateway-deployment.yaml
│   └── gateway-service.yaml
├── load-tests/
│   ├── smoke.js
│   └── authenticated.js
├── observability/
│   └── prometheus.yml
└── mock-upstream/
    └── hello
```

## Known limitations and scope

- The gateway deliberately supports the scoped `GET /proxy/**` route, not arbitrary HTTP methods or a general API-management feature set.
- Cached identity/policy entries can delay the effect of API-key revocation or policy changes.
- Local Jaeger uses temporary storage, and 100% trace sampling is intended for development evidence.
- The Kubernetes example deploys the gateway application only; its database, Redis, mock upstream, and tracing backend are external to the Kubernetes manifest.
- Kubernetes readiness indicates application availability but does not automatically check all dependencies.
- No production-scale availability, security hardening, or performance benchmark is claimed.

## Development workflow

Run `./mvnw test` after changes. Use `docker compose logs --tail=80 gateway` to troubleshoot the Compose deployment and `kubectl logs deployment/gateway --tail=80` for Kubernetes. Avoid committing credentials, raw API keys, database exports, or test results containing secrets.
