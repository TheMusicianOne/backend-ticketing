# 0004: Deployment and monitoring

**Status:** Accepted
**Date:** 2026-11-15
**Milestone:** 6 (Deploy and monitor)

## Context

The system has to run somewhere public, so that load tests hit a real server over a real network, and so the project can be shown. The goal of the milestone: **a push to `main` reaches the public URL, and its traffic appears in Grafana.**

Constraints: a portfolio budget (free or a few euros a month), one person operating it, and enough capacity to find meaningful limits under load.

---

## 1. Hosting: one Hetzner Cloud server

**Options**

| Option | Notes |
|---|---|
| Oracle Cloud Always Free | Free, generous (Arm VMs). **Signup was rejected** by its fraud checks, a common experience |
| Google Cloud free VM | Free, but 1 GB of memory: too small for Java, PostgreSQL and monitoring together |
| Managed platforms (Render, Fly.io, Railway) | Easy deploys, but hide the server: less to learn, and harder to measure where load goes |
| **Hetzner Cloud CX23 (chosen)** | 2 shared vCPUs, 4 GB, about €6 a month including an IPv4 address, billed hourly |

**Why**

- Cheap and predictable, with hourly billing, so extra servers for experiments cost cents.
- A plain Linux server: everything about running it (SSH, firewall, Docker, TLS) is visible and explainable.
- 4 GB fits the app (heap capped at 512 MB), PostgreSQL, the payment provider, Caddy, Prometheus and Grafana, at roughly 1.3 to 2 GB in total.

**Trade-offs we accept**

- **One server is a single point of failure.** If it goes down, everything goes down.
- **Everything shares 2 vCPUs.** Under load the app competes with PostgreSQL, the payment provider and monitoring. Load test 05 showed this directly. A real payment provider would run elsewhere.
- **Shared vCPUs vary a little between runs**, because other customers use the same physical machine.

---

## 2. Docker Compose for the whole system

One `compose.yaml` describes PostgreSQL, the payment provider, the app, Caddy, Prometheus and Grafana. Locally, `docker compose up` starts only the dependencies (for `./gradlew bootRun`); `--profile full` starts everything, exactly as on the server.

**Why**

- The same file runs on a laptop and on the server, so "works locally" means "works deployed".
- Services reach each other by name (`postgres:5432`, `ticketing-backend:8080`), not by IP.
- `depends_on` with health checks makes the app wait until PostgreSQL and the provider are ready.

**Alternatives considered:** Kubernetes (far more than one server needs), running the JAR directly with systemd (no isolation, and dependencies installed by hand).

The app's image is a **multi-stage build**: a JDK stage builds the JAR, and a smaller JRE stage runs it as a non-root user, without Gradle or source code.

---

## 3. HTTPS with Caddy, and names from sslip.io

**Caddy** sits in front of everything as a reverse proxy. It obtains certificates from Let's Encrypt automatically, renews them, and routes by host name:

| Name | Goes to |
|---|---|
| `api.<ip>.sslip.io` | The app |
| `pay.<ip>.sslip.io` | The payment provider's checkout page, which customers' browsers open |

**Why Caddy:** automatic certificates with a configuration file of a dozen lines. Nginx with Certbot would work too, with more moving parts.

**Why sslip.io:** certificates need a domain name, and sslip.io resolves any name containing an IP address to that IP, with nothing to register. A real domain only changes two environment variables.

**Why HTTPS matters here:** the API uses bearer tokens. Over plain HTTP, anyone on the network path could copy a token and use it.

TLS ends at Caddy; traffic inside Docker's private network is plain HTTP, which never leaves the server.

---

## 4. Server security

| Measure | Protects against |
|---|---|
| **Hetzner firewall: only ports 22, 80 and 443 inbound** | Anything else reaching the server, even if a container publishes a port by mistake. It sits outside the server, so Docker's own firewall rules can't bypass it |
| **SSH keys only, no password logins** | Password guessing by bots, which find new servers within minutes |
| **No root login over SSH**; a `deploy` user with `sudo` | A leaked key gives only an unprivileged user; `sudo` still needs a password |
| **PostgreSQL bound to `127.0.0.1`** | The database being reachable from the network, as a second layer behind the firewall |
| **Secrets in a `.env` file on the server only** (`chmod 600`), random values generated with `openssl rand` | Secrets in a public Git repository. `compose.yaml` reads them as `${NAME:-local default}`, so the same file works locally |
| **Metrics blocked at Caddy** (`/actuator/prometheus` answers `404` from the internet) | Leaking internal details. Prometheus scrapes the app directly inside Docker's network |
| **Prometheus and Grafana bound to `127.0.0.1`**, reached through an SSH tunnel | Admin tools exposed to the internet. Only someone with the SSH key can open them |

Each shared secret (the provider's API key, the webhook secret) comes from **one** variable used by both sides, so they can't drift apart.

---

## 5. Deploying from GitHub Actions

On every push to `main`:

```text
Java tests ─┐
            ├─ both pass? → SSH to the server → git pull --ff-only
Python tests┘                                → docker compose --profile full up -d --build
                                             → docker image prune
                                             → wait for https://api.../actuator/health
```

- GitHub Actions has **its own SSH key**, stored as a repository secret with the server's fingerprint, so it can be revoked without touching personal access.
- `needs:` means broken code never reaches the server.
- `concurrency:` makes a second push wait for the first deploy instead of running at the same time.
- `git pull --ff-only` refuses to merge changes made on the server, which enforces "never edit on the server".
- The **health check fails the job** if the app doesn't come up within 2.5 minutes, so a bad deploy shows as a red X.

**Trade-offs we accept**

- **Images are built on the server.** Simple, but it uses the server's CPU and takes a few minutes. The usual next step is building images in CI and pushing them to a registry, so the server only pulls finished images.
- **A few seconds of downtime per deploy**, while containers are replaced. Zero-downtime deployment would need a second instance and a switch-over.

---

## 6. Monitoring: Micrometer, Prometheus and Grafana

- **Micrometer** (built into Spring Boot) measures requests, JVM memory, CPU and the connection pool. `micrometer-registry-prometheus` exposes them at `/actuator/prometheus`, tagged with `application=ticketing-backend`.
- **Request histograms** (`percentiles-histogram` for `http.server.requests`) let Prometheus compute p95 response times, which matter more than averages.
- **Prometheus** scrapes every 15 seconds and keeps 15 days of data.
- **Grafana** gets its Prometheus data source from a provisioning file. The dashboard has six panels chosen for load tests:

| Panel | Why |
|---|---|
| Requests per second, per endpoint | Is the load arriving as planned? |
| p95 response time, per endpoint | The users' experience, inside the app |
| `5xx` errors per second | Real failures (`404` and `409` are normal answers) |
| Database connections: active, pending, wait time | The classic bottleneck. Pending above 0 means requests are queueing |
| JVM heap | Garbage collection pattern, and leaks |
| CPU: the app, and the whole server | Whether the app or something else uses the CPU |

**Lessons from using it**

- A brand-new error is invisible in rate graphs at first: there is no earlier value to compare with. Short bursts right after a restart can show as zero.
- **Under severe overload, metrics stopped** (load test 05): Prometheus could not get an answer within its scrape timeout. The moments of worst overload are exactly the ones a scrape-based monitor may miss.
- Comparing **Gatling's** (client) and **Grafana's** (server) response times separates network time from application time. That comparison solved the "slow first request" question in load test 01.

---

## Known limitations and future work

| Limitation | Possible fix |
|---|---|
| **No database backups** | Scheduled `pg_dump` to object storage, or Hetzner's server backups |
| One server: a single point of failure | A second server and a load balancer, or a managed database |
| The payment provider, monitoring and app share 2 vCPUs | Separate servers, at least for the provider during load tests |
| Images built on the server; a few seconds of downtime per deploy | Build in CI and push to a registry; blue-green or rolling deploys |
| No alerting: problems are only seen when someone looks | Grafana or Alertmanager alerts on errors, latency and disk space |
| sslip.io names tied to the server's IP | A real domain |
| Secrets in a file on disk | A secrets manager, if the setup grows |
| Grafana dashboard configured by hand | Store it as JSON in the repository and provision it ("dashboards as code") |
