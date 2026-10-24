# PROJECT_SCOPE.md

> Source of truth for this project.  
> If the chat becomes long or the project starts expanding, return to this file before adding new work.

## 1. Project Goal

**Project:** Distributed API Gateway / Rate-Limiting Platform

**Primary goal:**  
Build and understand a Java 21 / Spring Boot gateway that authenticates API keys, enforces **per-user and per-tenant quotas consistently across multiple gateway instances**, and forwards admitted requests to an upstream service. Use the project to demonstrate JVM concurrency, Redis atomic operations, caching, resilience, observability, load testing, and practical deployment skills.

**Portfolio / practical outcome:**  
A runnable, documented repository containing one Spring Boot gateway application, a small mock upstream, PostgreSQL and Redis infrastructure, automated tests, Docker Compose development setup, a minimal Kubernetes demo, Prometheus/Grafana monitoring, OpenTelemetry tracing, and reproducible k6 load-test results. I can explain both the successful request path and important failure paths in an interview, using measured results rather than unsupported performance claims.

**This is NOT intended to be:**  
A replacement for an enterprise API gateway, a globally distributed or multi-region rate limiter, a high-availability Redis platform, a full identity-management product, a sophisticated service mesh, or an exhaustive production-security/compliance implementation. "Production engineering" here means demonstrating selected, testable practices—not claiming the entire system is production ready.

**Portfolio framing:**  
The target is a credible **mid-level backend engineering demonstration**: correct behavior under concurrency, explicit tradeoffs, understandable design, resilience, troubleshooting, monitoring, and honest performance evidence.

---

## 2. Learning Priorities

My priorities, in order:

1. Understand the concepts.
2. Be able to explain the code without ChatGPT.
3. Build a working end-to-end project.
4. Keep the architecture understandable.
5. Add only the complexity needed to demonstrate the target skills.
6. Polish, document, and test the finished project.

**Understanding is more important than technical sophistication.**

For this project, give special attention to: **thread safety versus distributed atomicity**, **token-bucket math**, **cache consistency**, **failure policies**, **latency and throughput under load**, and **the meaning of telemetry signals**. Do not optimize a benchmark before understanding what it measures.

---

## 3. Definition of Done

The project is complete when all REQUIRED items below work. Each checkbox should be supported by a runnable demo, automated test, or documented verification step; a class existing in the codebase does not by itself satisfy the requirement.

### REQUIRED

- [ ] **R1 — Protected gateway request flow.** One configurable `/proxy/**` route forwards requests to one mock HTTP upstream; allowed responses are relayed back to the client. A local run works end to end.
- [ ] **R2 — API-key authentication and identity mapping.** Requests require a valid API key. PostgreSQL holds **hashes, not raw API keys**, with active status and associated `tenant_id` and `user_id`. A simple seed/provisioning script or development fixture is sufficient; missing/invalid keys return `401`, and secrets never appear in logs.
- [ ] **R3 — Per-user AND per-tenant policies.** Quota configuration identifies the user and tenant from the authenticated key. The same request must satisfy both limits; tests demonstrate that a user limit and a shared tenant limit each independently cause `429 Too Many Requests`.
- [ ] **R4 — Correct distributed token bucket.** Implement **token bucket** (not both algorithms) with atomic Redis-side operations, such as one Lua script that checks and conditionally consumes from both buckets together. Return `429` and a useful retry indication when rejected. Two gateway instances share the same limits. Rejected requests do not partially consume the other bucket.
- [ ] **R5 — Concurrency correctness.** Handle simultaneous HTTP requests using Spring Boot's normal request handling; add a parallel integration/load test showing that concurrent requests across both instances do not exceed the configured shared quota. Do **not** build a custom thread pool or lock manager just to demonstrate concurrency.
- [ ] **R6 — Local and distributed caching.** Cache API-key/identity or policy lookups with **Caffeine (local L1) and Redis (shared L2)**, using PostgreSQL as the source of truth. Document lookup order, TTLs, and the bounded staleness tradeoff. Verify cache hits and eventual refresh. Never cache raw API-key secrets.
- [ ] **R7 — Circuit breaker on an outbound call.** Use Resilience4j on the gateway-to-upstream HTTP call. Demonstrate normal forwarding, upstream errors/timeouts, an open circuit with a controlled error response (for example `503`), and recovery when the upstream becomes healthy. Do not use this to bypass quotas.
- [ ] **R8 — Audit logging.** Emit structured, searchable audit events for meaningful authorization/rate-limit decisions (accepted, invalid key, quota exceeded) with timestamp, request/trace ID, and non-secret identity identifiers where available. For this project, structured application logs count; a durable audit-event database is **ADVANCED**.
- [ ] **R9 — Metrics and dashboard.** Expose Spring Boot Actuator/Prometheus metrics, including request outcomes, allowed/rejected counts, latency, and relevant circuit-breaker behavior. Supply one working Grafana dashboard and demonstrate how to investigate a spike in `429` or upstream failures. Avoid API-key/user IDs as high-cardinality metric labels.
- [ ] **R10 — Distributed tracing.** Instrument inbound gateway requests and the outbound upstream HTTP call with OpenTelemetry-compatible tracing. Export and view traces locally (for example, through Jaeger) and confirm trace context is propagated to the mock upstream. A custom instrumented upstream application is not required.
- [ ] **R11 — Containers and Kubernetes probes.** Run the core demo with Docker Compose. Provide minimal Kubernetes manifests with a gateway Deployment/Service and separate Actuator **liveness** and **readiness** probes; verify their behavior in a local cluster. Liveness should not restart the app solely because PostgreSQL/Redis is briefly unavailable; readiness should reflect whether it can serve requests.
- [ ] **R12 — Failure behavior.** Document and test at least two key failures: Redis unavailable means **fail closed** (`503`, no silently unthrottled traffic), and an unavailable upstream is handled without crashing gateway request threads. Explain the choice and its availability tradeoff.
- [ ] **R13 — Automated tests and load-test evidence.** Use JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL/Redis), and an HTTP upstream stub. Include unit/integration tests for authentication, token math/boundaries, shared tenant quotas, concurrent requests, caching, and circuit breaker. Commit a repeatable **k6** scenario and a short results report covering throughput, latency (including p95), `429` rate, test environment, and what the results actually show.
- [ ] **R14 — Portfolio handoff.** Provide a README with prerequisites, setup/teardown, demo requests, diagram, main design decisions, failure policy, tests, observability steps, and performance results. Be able to trace the request flow and defend the tradeoffs without ChatGPT.

### OPTIONAL

These may improve the project but are not necessary to finish it.

- [ ] Add a small authenticated API for creating/revoking/rotating API keys (the REQUIRED project uses seeded/provisioned keys).
- [ ] Support different quota policies per route in addition to per-user/per-tenant quotas.
- [ ] Compare token bucket to a sliding-window implementation in a separate experiment; do not maintain both in the gateway by default.
- [ ] Add a focused performance comparison of the no-cache, L2-cache, and L1-cache paths.
- [ ] Add a richer Grafana dashboard or more load scenarios after the required observations work.

### ADVANCED / PRODUCTION HARDENING

These are intentionally out of scope unless I explicitly decide to implement them.

- [ ] Multi-region or strongly consistent global rate limits; Redis Cluster hash-slot handling, sharding, failover guarantees, or active-active coordination.
- [ ] Durable, exactly-once audit-event persistence, retention/legal compliance, and a background logging pipeline.
- [ ] OAuth/OIDC, self-service tenant onboarding, RBAC administration, billing, or a comprehensive key-management product.
- [ ] General-purpose gateway routing, service discovery, retries across many upstreams, mTLS, WAF, or service-mesh integration.
- [ ] Production HA for Redis/PostgreSQL/Kubernetes, autoscaling, disaster recovery, and full secret-management automation.
- [ ] Extensive chaos engineering, formal performance SLOs, or exhaustive edge-case hardening.

**Do not move OPTIONAL or ADVANCED items into implementation automatically.**

**Behavioral boundaries for REQUIRED work:** One token per admitted request, checked against both the user and tenant buckets. A request consumes quota when the gateway **admits the request**, even if the upstream later fails. Cache entries use finite TTLs rather than implementing a distributed invalidation protocol. Redis is the authority for live token counts; PostgreSQL is the authority for API-key identities and quota configuration. Use a single Redis deployment for the portfolio demo, not Redis Cluster.

---

## 4. Architecture Boundary

Current intended architecture:

```text
Client / k6
    |
    v
Spring Boot Gateway (one application; run 2 instances for distributed tests)
    |  API-key authentication -> L1 Caffeine -> L2 Redis cache -> PostgreSQL
    |  user + tenant token-bucket decision -> atomic Redis operation
    |  audit event, metrics, trace spans
    |  outbound HTTP client protected by Resilience4j
    v
One mock upstream HTTP service

Gateway instances ----> Redis: shared bucket state and L2 metadata cache
Gateway instances ----> PostgreSQL: API keys and quota policies (source of truth)
Gateway instances ----> Prometheus / Grafana: metrics
Gateway instances ----> local trace viewer via OTLP: traces
```

Main components:

- **Gateway application** — Authenticate, resolve identity/policy, enforce limits, forward admitted traffic, produce telemetry and audit events. This is the **only custom deployable application** required.
- **PostgreSQL** — Store hashed API keys, key status, identities, and quota policies. No need for complex administration tables.
- **Redis** — Hold shared token-bucket state and shared cache entries; an atomic script prevents cross-instance oversubscription and partial user/tenant consumption.
- **Caffeine** — Local bounded-TTL cache in each gateway process; a performance optimization, **not** a rate-limit authority.
- **Mock upstream** — A deliberately simple HTTP test target (for example WireMock), including controllable failures/timeouts for circuit-breaker tests.
- **Observability tools** — Actuator/Micrometer/Prometheus/Grafana plus OpenTelemetry export and a local trace viewer. Tooling supports a REQUIRED demo rather than creating a new business service.
- **Docker Compose / local Kubernetes** — Two-instance shared-state demonstration in Compose and probe/deployment demonstration in Kubernetes. Kubernetes files are educational/dev-only manifests, not an HA reference architecture.

**Important request outcomes:** `401` for invalid credentials, `429` for a valid principal over quota, `503` when throttling cannot be safely evaluated because Redis is unavailable, and a controlled `5xx` for upstream/circuit-breaker failures. Never return a successful upstream response without a successful shared quota decision.

**Limit the scope:** One route family, one gateway codebase, one algorithm, one upstream test target, one Redis deployment, and one PostgreSQL database. Do not introduce Kafka, a custom event bus, separate authentication microservice, or custom distributed locks.

Do not add a new service, database, queue, framework, abstraction, or infrastructure component unless it is necessary for a REQUIRED feature or I explicitly approve it.

---

## 5. Technology Choices

Use these unless there is a strong reason to change them:

- **Language:** Java 21
- **Framework:** Spring Boot (Spring MVC/Actuator; keep the request flow understandable)
- **Rate-limiting algorithm:** Token bucket; atomic Redis operation for both quota levels
- **Distributed state + L2 cache:** Redis
- **Database / source of truth:** PostgreSQL
- **L1 cache:** Caffeine
- **HTTP resiliency:** Resilience4j circuit breaker with configured timeouts
- **Metrics:** Micrometer + Prometheus + Grafana
- **Tracing:** Micrometer/OpenTelemetry integration with OTLP export; local trace viewer (for example Jaeger)
- **Logging:** Structured JSON application/audit logs, correlated with request/trace IDs
- **Testing:** JUnit 5, Spring Boot Test, Testcontainers, WireMock or equivalent HTTP stub
- **Load testing:** k6 (choose one tool; no simultaneous Gatling/JMeter setup)
- **Build tool:** Maven
- **Infrastructure:** Docker Compose for local end-to-end run; minimal Kubernetes YAML and a local kind/minikube cluster for probes
- **Messaging:** None required

Avoid replacing technologies mid-project just because another option may be more sophisticated. Prefer a small number of understandable dependencies to an elaborate gateway framework unless an actual REQUIRED implementation problem makes one necessary.

---

## 6. Teaching Contract for ChatGPT

Act as my programming teacher, not as an architect trying to maximize production complexity.

Before suggesting or implementing a new feature, classify it as:

- **REQUIRED** — necessary for the Definition of Done.
- **OPTIONAL** — useful, but not necessary.
- **ADVANCED** — production hardening, unusual edge-case handling, or complexity beyond the learning goal.

Rules:

1. Follow this file as the source of truth.
2. Teach one small concept at a time.
3. Explain the problem before giving me code.
4. Prefer the simplest correct implementation that demonstrates the concept.
5. Do not continuously add “one more improvement” after something works.
6. Do not implement OPTIONAL or ADVANCED work unless I explicitly approve it.
7. Mention production improvements briefly, then park them in the backlog.
8. If a change introduces several new abstractions, tables, services, or workflows, stop and check whether it is required.
9. Do not refactor working code only to make it more theoretically elegant unless the current code is causing a real problem.
10. Keep changes small enough that I can understand what changed and why.
11. If a change touches most of a file, give me the full updated file rather than many scattered edits.
12. After each major milestone, stop adding code and review the system with me.
13. Make me trace important flows in my own words before adding another major feature.
14. If I cannot explain an existing feature, prioritize understanding it before building on top of it.
15. Tell me clearly when the original Definition of Done has been reached.

**Project-specific teaching rule:** Before implementing a distributed or concurrency feature, first explain what would fail with a naive single-instance/in-memory approach, then show how the selected design addresses that failure and what it still does not guarantee.

---

## 7. Scope Check Before New Work

Before starting a significant new feature, ChatGPT should answer:

**What problem does this solve?**  
[Short explanation tied to a behavior or observable failure.]

**Classification:**  
REQUIRED / OPTIONAL / ADVANCED

**Which Definition-of-Done item requires it?**  
[R1–R14, or “none”.]

**Complexity added:**  
[Files, services, tables, dependencies, concepts, etc.]

**Decision:**  
Implement now / Park for later

If the classification is OPTIONAL or ADVANCED, the default decision is **Park for later**. A performance or production-sounding benefit is not sufficient to reclassify it as REQUIRED.

---

## 8. Current Milestone

**Milestone:** M1 — Single-instance protected request path

**What we are working on now:**  
Bootstrap one Spring Boot gateway and one mock upstream. Prove a request can be forwarded through `/proxy/**`, then add basic API-key lookup backed by PostgreSQL so valid keys succeed and missing/invalid keys return `401`. Focus on understanding the synchronous request flow before adding Redis.

**What we are NOT working on right now:**

- Redis token buckets, distributed counters, caching, or two-instance behavior.
- Circuit breakers, dashboards, tracing, or load-test tuning.
- Kubernetes infrastructure or optional API-key management endpoints.

**Next checkpoint:**  
Using one curl request and one automated test, I can trace **client → gateway → API-key lookup → mock upstream → response**, explain why each class is needed, and demonstrate the invalid-key path.

**Planned milestone order (guide, not permission to expand scope):**

1. **M1 — Working protected forwarding:** R1 and the basic identity/authentication portion of R2.
2. **M2 — Shared quotas and concurrency:** Finish R2; R3–R5, R12's Redis failure policy.
3. **M3 — Caching and resilience:** R6–R7 and remaining R12 failure tests.
4. **M4 — Observability:** R8–R10.
5. **M5 — Deployment and evidence:** R11, R13–R14; final explanation and scope review.

After every milestone, review the working system and understanding before proceeding.

---

## 9. Understanding Checkpoint

Before moving to the next major milestone, I should be able to explain:

- What starts this flow?
- Which component handles it first?
- What data changes?
- What message/API call happens next?
- Why is each major class/service present?
- What happens on the normal path?
- What happens on one important failure path?
- What prevents duplicate or inconsistent work?
- How would I explain this feature in an interview?

For this project, I should additionally be able to explain:

- Why `synchronized` or an in-memory counter on one JVM cannot enforce a global limit across two gateway instances.
- How token capacity, refill rate, elapsed time, and atomic check-and-consume work; why checking the user bucket and tenant bucket separately is unsafe.
- What changes when an API-key policy is cached locally versus in Redis, and why TTL means updates can be stale temporarily.
- Why rate limiting fails closed when Redis is unavailable, and the operational cost of that choice.
- How to distinguish a `429` caused by policy from a circuit-breaker `5xx`, using logs, metrics, and a trace.
- What a concurrency/load test proves—and does **not** prove—about production capacity.

If I cannot answer these, pause implementation and review instead of adding more features.

---

## 10. Complexity Warning Signs

Stop and review scope if any of these start happening:

- One feature creates many new services/classes.
- We add infrastructure not present in the original architecture.
- We are solving failures inside failure-recovery mechanisms.
- We are adding coordination for extremely rare edge cases.
- Tests are growing faster than my understanding.
- I can no longer trace the main request/event flow.
- I am copying code without understanding why it exists.
- A feature is justified mainly with “a real production system might...”.
- We keep improving something that already satisfies the project goal.
- A request for "distributed" behavior turns into a Redis Cluster, messaging, or multi-region design without a failing REQUIRED test.
- A request for "production" behavior turns into an entire authentication platform or general-purpose gateway.

When one of these happens, ask:

> Is this required by the original Definition of Done?

If not, park it.

---

## 11. Parked Ideas / Later

Ideas go here instead of immediately becoming implementation work.

| Idea | Classification | Why parked |
|---|---|---|
| API-key creation, rotation, revocation API | OPTIONAL | Seeded, hashed keys are enough to demonstrate request authentication. |
| Per-route, tiered, or burst-specific quota products | OPTIONAL | A single per-user + per-tenant policy proves hierarchical enforcement. |
| Sliding-window alternative | OPTIONAL | One correct token bucket is better than maintaining two algorithms. |
| Cache-invalidation pub/sub | ADVANCED | Bounded TTLs are enough for this demo; instant consistency adds coordination. |
| Durable audit database, ingestion queue, retention | ADVANCED | Structured logs demonstrate audit context without making every request depend on an audit write. |
| Redis Cluster, cross-shard atomicity, failover tuning | ADVANCED | Single Redis shared state demonstrates distributed gateway correctness. |
| OAuth/OIDC and full gateway policy engine | ADVANCED | Not needed for API-key authenticated forwarding. |
| Autoscaling, load balancing appliances, multi-region deployment | ADVANCED | Two directly addressable Compose replicas plus a minimal Kubernetes Service are sufficient. |

---

## 12. Progress Log

Keep this short. Record milestones, not every tiny change.

### 2026-10-10 — Scope established (planning only)

- **Completed:** Defined the intended architecture, R1–R14 acceptance criteria, technology choices, and scope boundaries. No implementation is claimed.
- **Learned:** Not yet assessed; review during M1.
- **Still need to understand:** The protected request path and how identity is derived from an API key.
- **Next:** Start M1 and check off R1/R2 only when the code and tests work.

---

## 13. Final Project Checklist

Before declaring the project finished:

- [ ] Every REQUIRED item (R1–R14) works.
- [ ] Main end-to-end flow works: authenticate → resolve policy → atomically enforce user and tenant limits → proxy → respond.
- [ ] Important failure paths work: invalid key, exhausted quota, Redis unavailable, upstream/circuit open.
- [ ] Tests cover the concepts I intended to demonstrate, including two-instance concurrency correctness.
- [ ] I can explain the architecture without ChatGPT.
- [ ] I can trace one request/event end to end.
- [ ] README explains setup and architecture.
- [ ] Architecture diagram is understandable.
- [ ] Prometheus/Grafana and a trace viewer show useful signals; audit logs are correlated without exposing credentials.
- [ ] k6 results are reproducible and include methodology/limitations, not inflated benchmark claims.
- [ ] Kubernetes liveness/readiness probes are demonstrated in a local cluster.
- [ ] Optional complexity has been removed or clearly isolated.
- [ ] Resume bullets accurately describe what I built.
- [ ] I can discuss design tradeoffs in an interview.

**Resume bullet (use only after verifying the claims):**  
> Engineered a distributed rate-limiting gateway using Java 21, Spring Boot and Redis, implementing atomic per-user/per-tenant token-bucket throttling across concurrent instances, resilient caching, circuit breakers, and Prometheus/OpenTelemetry observability; validated behavior under k6 load.

---

## 14. Long-Chat Reset Prompt

If this ChatGPT conversation becomes very long, paste this:

> Re-read `PROJECT_SCOPE.md` and use it as the source of truth. Before continuing, briefly restate the project goal, current milestone, remaining REQUIRED Definition-of-Done items (R1–R14), and whether the next proposed step is REQUIRED, OPTIONAL, or ADVANCED. Do not add optional production hardening unless I explicitly approve it.

---

## 15. Emergency Scope Reset

If the project starts feeling out of control, use:

> Stop adding features. Compare the current codebase with `PROJECT_SCOPE.md`. Identify which parts are REQUIRED, OPTIONAL, and ADVANCED. Do not delete anything yet. Help me restore a working build, then switch to understanding and simplification mode. We will not add new features until I can explain the existing main flow.
