-- Users: a single table with a role column (ORGANIZER | CUSTOMER)
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT        NOT NULL,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL CHECK (role IN ('ORGANIZER', 'CUSTOMER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id                BIGSERIAL PRIMARY KEY,
    organizer_id      BIGINT      NOT NULL REFERENCES users (id),
    title             TEXT        NOT NULL,
    description       TEXT,
    venue             TEXT        NOT NULL,
    starts_at         TIMESTAMPTZ NOT NULL,
    total_tickets     INT         NOT NULL CHECK (total_tickets > 0),
    -- Denormalised counter so a booking is ONE conditional UPDATE (no COUNT(*) over bookings).
    available_tickets INT         NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Last line of defence against overselling, even if application code is wrong.
    CONSTRAINT available_in_range CHECK (available_tickets >= 0 AND available_tickets <= total_tickets)
);
CREATE INDEX idx_events_upcoming ON events (starts_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_events_organizer ON events (organizer_id);

CREATE TABLE bookings (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT      NOT NULL REFERENCES events (id),
    customer_id  BIGINT      NOT NULL REFERENCES users (id),
    quantity     INT         NOT NULL CHECK (quantity > 0),
    status       TEXT        NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ
);
CREATE INDEX idx_bookings_event ON bookings (event_id) WHERE status = 'CONFIRMED';
CREATE INDEX idx_bookings_customer ON bookings (customer_id, created_at DESC);

-- In-app notifications (the persistent, queryable side of "notify customers")
CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    event_id   BIGINT REFERENCES events (id),
    type       TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    message    TEXT        NOT NULL,
    is_read    BOOLEAN     NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

-- Audit trail of every email attempt (proof that real emails went out)
CREATE TABLE email_log (
    id                  BIGSERIAL PRIMARY KEY,
    recipient           TEXT        NOT NULL,
    subject             TEXT        NOT NULL,
    status              TEXT        NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SUPPRESSED')),
    provider            TEXT,
    provider_message_id TEXT,
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Durable job queue (transactional outbox). Jobs are inserted in the SAME transaction
-- as the booking / event update, so a committed booking can never lose its email.
CREATE TABLE jobs (
    id           BIGSERIAL PRIMARY KEY,
    type         TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts     INT         NOT NULL DEFAULT 0,
    max_attempts INT         NOT NULL DEFAULT 5,
    run_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at    TIMESTAMPTZ,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_ready ON jobs (run_at) WHERE status = 'PENDING';
CREATE INDEX idx_jobs_running ON jobs (locked_at) WHERE status = 'RUNNING';
