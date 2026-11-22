# Event Booking System: Backend APIs

A Spring Boot 3 / Java 21 REST API for an event booking platform with two roles: **Event Organizers** manage events, **Customers** browse and book tickets. Bookings and event updates trigger **background jobs** that send **real emails** and create in-app notifications. The booking path is built and load-tested for high concurrency, with a switchable **baseline vs optimized** implementation so the performance gain can be measured.

- **Live URL:** `https://<your-app>.onrender.com`  ← fill in
- **Demo video:** `<loom link>`  ← fill in

---

## 1. Tech stack and why

| Concern | Choice | Why |
|---|---|---|
| Language / framework | Java 21, Spring Boot 3.3 | Mature, production-standard; Java 21 **virtual threads** matter for the performance part. |
| Database | PostgreSQL | ACID transactions and row-level locking are exactly what ticket inventory needs. Free on Render/Neon. |
| Data access | Spring `JdbcClient` (plain SQL), no JPA | The hot path is a single hand-tuned SQL statement. Explicit SQL makes the concurrency behaviour obvious and avoids ORM overhead (dirty checking, extra SELECTs). |
| Migrations | Flyway | Schema is versioned and applied automatically on startup. |
| Auth | Stateless JWT (HS256, jjwt) + BCrypt | No server-side session, so it scales horizontally. The role travels inside the token. |
| RBAC | Spring Security URL rules + `@PreAuthorize` | Role checks are declarative and in one place. Ownership checks are in the service layer. |
| Job queue | **Postgres-backed queue** (`jobs` table, `FOR UPDATE SKIP LOCKED`) | Durable, retryable, multi-instance safe, and **no extra infrastructure** (no Redis/RabbitMQ to host on a free tier). Enables the transactional outbox pattern (see §5). |
| Email | Brevo HTTPS API (default) or any SMTP server | Real delivery. The HTTPS API is used because many free hosts (Render included) block outbound SMTP ports. |
| Load testing | k6 | Scriptable, reports p95 and error rates, runs on Windows/Mac/Linux. |
| Hosting | Render (Docker) + Render Postgres | Free tier, one-click Blueprint (`render.yaml`). |

---

## 2. Architecture

```
            ┌──────────────── Spring Boot app (one container) ────────────────┐
 client ──► │ JwtAuthFilter → Security (RBAC) → Controllers → Services         │
            │                                      │  same DB transaction      │
            │                                      ▼                           │
            │                       bookings / events  +  jobs (outbox row)    │
            │                                                                  │
            │ JobWorker (@Scheduled poll, SKIP LOCKED, 8 threads)              │
            │   BOOKING_CONFIRMED → notification + SEND_EMAIL job              │
            │   EVENT_UPDATED     → notifications + 1 SEND_EMAIL job/customer  │
            │   SEND_EMAIL        → Brevo API / SMTP → email_log               │
            └──────────────────────────────────┬───────────────────────────────┘
                                               ▼
                                          PostgreSQL
```

The worker runs **inside the web process** by default, because the free tier has no background-worker service. Because the queue lives in Postgres and claims jobs with `SKIP LOCKED`, the same jar can run as a separate worker fleet with `JOBS_ENABLED=false` on web nodes and `true` on worker nodes, without code changes.

---

## 3. Data model

```
users(id, name, email UNIQUE, password_hash, role ∈ {ORGANIZER, CUSTOMER})
events(id, organizer_id→users, title, description, venue, starts_at,
       total_tickets, available_tickets, status ∈ {ACTIVE, CANCELLED})
       CHECK (0 <= available_tickets <= total_tickets)
bookings(id, event_id→events, customer_id→users, quantity, status ∈ {CONFIRMED, CANCELLED})
notifications(id, user_id, event_id, type, title, message, is_read)
email_log(id, recipient, subject, status ∈ {SENT, FAILED, SUPPRESSED}, provider, provider_message_id, error)
jobs(id, type, payload JSONB, status ∈ {PENDING, RUNNING, DONE, FAILED}, attempts, max_attempts, run_at, locked_at, last_error)
```

Design decisions:
- **One `users` table with a `role` column**, not two tables. Both roles share auth. A user has exactly one role, which keeps RBAC simple.
- **`available_tickets` is a denormalised counter** on `events`. Checking availability is then O(1) and booking is a single conditional `UPDATE`, rather than `SUM(quantity)` over bookings under a lock.
- **The DB `CHECK` constraint is the last line of defence** against overselling, even if application code had a bug.
- **Partial indexes** (`WHERE status='ACTIVE'`, `WHERE status='PENDING'`) keep hot indexes small.
- **Cancelled events and bookings are soft states**, never deleted. This keeps history and audit.
- Money/pricing and payments are **out of scope** (not in the brief).

---

## 4. API reference

Auth: `Authorization: Bearer <token>`. All errors look like `{ "status": 409, "error": "Conflict", "message": "Event is sold out", ... }`.

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | `{name, email, password, role: ORGANIZER\|CUSTOMER}` → `{token, user}` |
| POST | `/api/auth/login` | public | `{email, password}` → `{token, user}` |
| GET | `/api/auth/me` | any | Current user |
| GET | `/api/events?q=&page=&size=` | public | Upcoming active events, search by title/venue, paginated |
| GET | `/api/events/{id}` | public | Event details incl. `availableTickets` |
| POST | `/api/events` | ORGANIZER | Create `{title, description, venue, startsAt, totalTickets}` |
| PATCH | `/api/events/{id}` | ORGANIZER (owner) | Partial update → **triggers Background Task 2** |
| DELETE | `/api/events/{id}` | ORGANIZER (owner) | Cancel event (soft) → notifies ticket holders |
| GET | `/api/organizer/events` | ORGANIZER | My events |
| GET | `/api/organizer/events/{id}/bookings` | ORGANIZER (owner) | Bookings + tickets sold |
| GET | `/api/organizer/ops/stats` | ORGANIZER | Job queue and email-log counters (demo/observability) |
| POST | `/api/events/{id}/bookings` | CUSTOMER | `{quantity: 1..10}` → **triggers Background Task 1** |
| GET | `/api/bookings/me` | CUSTOMER | My bookings |
| POST | `/api/bookings/{id}/cancel` | CUSTOMER (owner) | Cancel and return tickets to the pool |
| GET | `/api/notifications/me?unreadOnly=` | any | In-app notifications |
| POST | `/api/notifications/{id}/read` | any (owner) | Mark read |
| GET | `/actuator/health` | public | Health check |

`demo-requests.http` has the whole flow ready to click through, in IntelliJ or the VS Code REST Client.

**Access control has two layers:**
1. **Role** (is this an organizer?): Spring Security URL rules, returns 403.
2. **Ownership** (is this *your* event or booking?): checked in the service against the JWT's user id, returns 403 or 404.

**Status codes:** 201 created, 400 validation, 401 no/invalid token, 403 wrong role or not owner, 404 not found, 409 business conflict (sold out, cancelled, started), 503 server overloaded (with `Retry-After`).

---

## 5. Background tasks

### Durable queue + transactional outbox
`JobQueue.enqueue()` inserts a row into `jobs` **inside the same DB transaction** as the booking or event update. Either both commit or neither does, so:
- a booking can never exist without its confirmation job (no lost emails if the app crashes right after responding);
- a rolled-back booking never sends an email.

This is safer than firing `@Async` or publishing to an external broker after commit, where a crash in between loses the message.

**Worker:** `JobWorker` polls every 500 ms. It claims a batch with
`UPDATE jobs SET status='RUNNING' ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50)`
and runs the batch on a thread pool. `SKIP LOCKED` lets many workers or instances share the queue with no double processing.
- **Retries:** exponential backoff (2, 4, 8, 16 s), up to 5 attempts, then `FAILED` with `last_error` kept.
- **Crash recovery:** jobs stuck in `RUNNING` for more than 5 min are re-queued.
- **Delivery semantics:** at-least-once. A crash between sending and marking done could re-send one email, which is the accepted trade-off for never losing one.

### Task 1: Booking confirmation
`BOOKING_CONFIRMED` → loads booking, event and customer → one transaction that inserts an in-app notification and enqueues a `SEND_EMAIL` job → `SendEmailHandler` sends an HTML email through Brevo/SMTP and records the result in `email_log`.

### Task 2: Event update notification
`PATCH /api/events/{id}` computes a human-readable diff (e.g. `Venue: Hall A → Hall B`). It enqueues `EVENT_UPDATED` only if a customer-visible field changed. The handler does the fan-out **set-based in SQL**, with no Java loop over customers:
- one `INSERT … SELECT DISTINCT customer_id` creates notifications for every ticket holder;
- one `INSERT … SELECT` creates **one `SEND_EMAIL` job per customer**, so each email retries independently and one bad address can't block the rest.

Cancelling an event uses the same path with a "cancelled" email.

### Why emails are real
`EmailService` calls the Brevo transactional API (or SMTP), stores the provider message id in `email_log`, and throws on failure to trigger a retry. Addresses at `@loadtest.local` are logged as `SUPPRESSED`, so stress tests don't burn the 300/day free quota.

---

## 6. Concurrency correctness (no overselling)

The optimized booking is **one SQL statement**:

```sql
WITH reserved AS (
  UPDATE events SET available_tickets = available_tickets - :qty
   WHERE id = :eventId AND status = 'ACTIVE' AND starts_at > now()
     AND available_tickets >= :qty
  RETURNING id)
INSERT INTO bookings (event_id, customer_id, quantity, status)
SELECT id, :customerId, :qty, 'CONFIRMED' FROM reserved
RETURNING *;
```

Under Postgres' READ COMMITTED isolation, concurrent `UPDATE`s on the same row serialise. Each one re-evaluates the `WHERE` against the latest committed row, so the check and the decrement are atomic. If no row matched, nothing is inserted and the API returns 409 with the precise reason.

**Verified:** 400 concurrent buyers racing for 100 tickets → exactly **100 booked, 0 available**, no oversell. Reproduce against the deployment with `loadtest/oversell.js`.

---

## 7. Performance: methodology, baseline, optimizations

### How to measure
```bash
# breaking point: step concurrency up 10 → 800 users, 20s each
.\loadtest\run-steps.ps1 -BaseUrl https://<app>.onrender.com        # Windows
./loadtest/run-steps.sh https://<app>.onrender.com                  # Mac/Linux

# single run, with concurrent event updates on the same event
k6 run -e BASE_URL=... -e VUS=200 -e UPDATE_RPS=5 loadtest/booking.js

# correctness under a race
k6 run -e BASE_URL=... -e VUS=300 -e TICKETS=100 loadtest/oversell.js
```

Each run prints a line like:
`VUS=200 requests=… ok=… throughput=…/s p50=…ms p95=…ms 503=… dropped=… errorRate=…%`

**Breaking point definition:** the concurrency level where the error rate (timeouts, connection errors, 5xx) rises above ~1%, or p95 latency goes past ~2 s. Past this point, adding users adds failures, not throughput.

### Baseline: the initial implementation (`BOOKING_MODE=baseline`, `VIRTUAL_THREADS=false`)
This is the typical first-cut code:
1. `SELECT … FOR UPDATE` the event row, check availability in Java, `UPDATE` the counter, `INSERT` the booking. That is **4 round trips with the row lock held across them**, so every booking for a popular event waits behind the previous one.
2. The auth filter **reloads the user from the DB on every request**: an extra query and a pool checkout.
3. 200 platform Tomcat threads compete for 10 DB connections; when the pool is empty, requests block until timeout.

### Optimized (`BOOKING_MODE=optimized`, `VIRTUAL_THREADS=true`)
| # | Optimization | Effect |
|---|---|---|
| 1 | **Single atomic conditional UPDATE + INSERT (CTE)** instead of lock → read → write | Row lock held for microseconds instead of several network round trips. Biggest win on a hot event. |
| 2 | **Stateless JWT auth**, no user lookup per request | One fewer query and pool checkout per request. |
| 3 | **Transactional outbox + async workers**: email is never in the request path | Booking latency is independent of the email provider's latency. |
| 4 | **Java 21 virtual threads** | Thousands of concurrent requests without a 200-thread ceiling or per-thread memory. |
| 5 | **Small, fixed Hikari pool + fast-fail (5 s) → 503 + `Retry-After`** | Postgres performs best with few connections (≈ 2×cores). Fail-fast sheds load cleanly instead of timing out. |
| 6 | **Indexes / partial indexes** on hot lookups; set-based SQL fan-out for notifications | Constant-time paths. One statement notifies N customers. |

### Results  ← fill in from your runs

| Concurrent users | Baseline ok/s | Baseline p95 | Baseline error % | Optimized ok/s | Optimized p95 | Optimized error % |
|---|---|---|---|---|---|---|
| 50  | | | | | | |
| 100 | | | | | | |
| 200 | | | | | | |
| 400 | | | | | | |
| 800 | | | | | | |

**Breaking point:** baseline ≈ ___ users → optimized ≈ ___ users (environment: Render free, 0.1 CPU / 512 MB, Postgres free).

### Further optimizations (next steps at larger scale)
- **Horizontal scaling:** the app is stateless (JWT + DB queue), so add instances behind the load balancer. Add **PgBouncer** (transaction pooling) so many instances share few Postgres connections.
- **Hot-row sharding for flash sales:** split one event's inventory into N counter rows (`event_inventory(event_id, shard, available)`) and pick a random shard, so N bookings proceed in parallel instead of serialising on one row.
- **Redis inventory gate:** atomic `DECRBY` in Redis rejects sold-out requests before touching Postgres; Postgres stays the source of truth.
- **Async booking (queue-based admission):** for extreme spikes, return `202 Accepted` with a booking id, process bookings from a queue at the DB's sustainable rate, and let clients poll. This trades latency for zero drops.
- **Rate limiting per user/IP** (bucket4j) and **caching** of `GET /api/events` (Caffeine/Redis, short TTL).
- **Read replicas** for browsing traffic.
- Dedicated worker instances (`JOBS_ENABLED`) and, at very high volume, a broker (RabbitMQ/Kafka/SQS) in place of the DB queue.

---

## 8. Running locally

**Docker (easiest):**
```bash
docker compose up --build            # API on http://localhost:8080, Postgres on 5432
BOOKING_MODE=baseline VIRTUAL_THREADS=false docker compose up --build   # baseline profile
```

**Without Docker:** Java 21 + Maven + a Postgres database:
```bash
export DB_HOST=localhost DB_USER=postgres DB_PASSWORD=postgres DB_NAME=eventbooking
mvn spring-boot:run
```
Flyway creates the schema on startup.

## 9. Deploying to Render
1. Push this repo to GitHub.
2. Render → **New → Blueprint** → select the repo. `render.yaml` creates the Postgres DB and the Docker web service.
3. In the service's **Environment** tab set `BREVO_API_KEY` and `EMAIL_FROM`. The sender must be verified in Brevo: Brevo → Senders → add and verify your email.
4. To record the baseline, set `BOOKING_MODE=baseline` and `VIRTUAL_THREADS=false`, redeploy, and run the load test. Then switch back to `optimized` / `true`.

Free-tier notes: the service sleeps after 15 min idle (first request takes about 30–60 s), and the free Postgres expires after 30 days.

## 10. Email setup (Brevo, free 300/day)
1. Sign up at brevo.com → **Senders, Domains & Dedicated IPs → Senders** → add your email and verify it.
2. **SMTP & API → API Keys** → create a key, then set `BREVO_API_KEY` and `EMAIL_FROM`.
3. SMTP alternative (local runs): `EMAIL_PROVIDER=smtp` with Gmail (`smtp.gmail.com:587`, an App Password) or Brevo's SMTP relay.

## 11. Assumptions
- One role per account. Organizers cannot book; customers cannot create events.
- Max 10 tickets per booking. Bookings close when the event starts.
- Reducing `totalTickets` below tickets already sold is rejected (409).
- Capacity-only changes don't notify customers. Changes to title, description, venue, or time do.
- Cancelling an event keeps the bookings (for refunds and audit) and notifies holders.
