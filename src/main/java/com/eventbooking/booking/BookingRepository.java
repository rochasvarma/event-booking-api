package com.eventbooking.booking;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BookingRepository {

    public record Booking(long id, long eventId, long customerId, int quantity, String status,
                          OffsetDateTime createdAt, OffsetDateTime cancelledAt) {}

    static final RowMapper<Booking> MAPPER = (rs, i) -> new Booking(
            rs.getLong("id"), rs.getLong("event_id"), rs.getLong("customer_id"), rs.getInt("quantity"),
            rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("cancelled_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * OPTIMIZED booking: one statement, one round trip.
     * The conditional UPDATE only succeeds if enough tickets remain; Postgres row-locks the event
     * row just for the duration of this statement (microseconds) and re-checks the WHERE clause
     * against the latest row version, so concurrent bookings can never oversell.
     * If the UPDATE matched nothing, the INSERT inserts nothing and we get an empty result.
     */
    public Optional<Booking> bookAtomically(long eventId, long customerId, int qty) {
        return jdbc.sql("""
                        WITH reserved AS (
                            UPDATE events
                               SET available_tickets = available_tickets - :qty, updated_at = now()
                             WHERE id = :eventId
                               AND status = 'ACTIVE'
                               AND starts_at > now()
                               AND available_tickets >= :qty
                            RETURNING id)
                        INSERT INTO bookings (event_id, customer_id, quantity, status)
                        SELECT id, :customerId, :qty, 'CONFIRMED' FROM reserved
                        RETURNING *""")
                .param("eventId", eventId).param("customerId", customerId).param("qty", qty)
                .query(MAPPER).optional();
    }

    /**
     * OPTIMIZED v2: reserve tickets, insert the booking AND enqueue the confirmation job in a
     * single statement, run in auto-commit mode. One network round trip, no separate COMMIT,
     * and the event row lock is released the moment the statement finishes.
     * The outbox guarantee still holds: a single statement is atomic.
     */
    public Optional<Booking> bookWithOutbox(long eventId, long customerId, int qty) {
        return jdbc.sql("""
                        WITH reserved AS (
                            UPDATE events
                               SET available_tickets = available_tickets - :qty, updated_at = now()
                             WHERE id = :eventId
                               AND status = 'ACTIVE'
                               AND starts_at > now()
                               AND available_tickets >= :qty
                            RETURNING id),
                        booked AS (
                            INSERT INTO bookings (event_id, customer_id, quantity, status)
                            SELECT id, :customerId, :qty, 'CONFIRMED' FROM reserved
                            RETURNING *),
                        job AS (
                            INSERT INTO jobs (type, payload)
                            SELECT 'BOOKING_CONFIRMED', jsonb_build_object('bookingId', id) FROM booked)
                        SELECT * FROM booked""")
                .param("eventId", eventId).param("customerId", customerId).param("qty", qty)
                .query(MAPPER).optional();
    }

    /** BASELINE helpers (used together with SELECT ... FOR UPDATE in the service). */
    public void decrementAvailable(long eventId, int newAvailable) {
        jdbc.sql("UPDATE events SET available_tickets = :a, updated_at = now() WHERE id = :id")
                .param("a", newAvailable).param("id", eventId).update();
    }

    public Booking insert(long eventId, long customerId, int qty) {
        return jdbc.sql("""
                        INSERT INTO bookings (event_id, customer_id, quantity, status)
                        VALUES (:eventId, :customerId, :qty, 'CONFIRMED') RETURNING *""")
                .param("eventId", eventId).param("customerId", customerId).param("qty", qty)
                .query(MAPPER).single();
    }

    /** Cancel a customer's own booking and return its tickets to the pool, atomically. */
    public Optional<Booking> cancel(long bookingId, long customerId) {
        return jdbc.sql("""
                        WITH c AS (
                            UPDATE bookings SET status = 'CANCELLED', cancelled_at = now()
                             WHERE id = :id AND customer_id = :customerId AND status = 'CONFIRMED'
                            RETURNING *),
                        r AS (
                            UPDATE events e SET available_tickets = e.available_tickets + c.quantity, updated_at = now()
                              FROM c WHERE e.id = c.event_id)
                        SELECT * FROM c""")
                .param("id", bookingId).param("customerId", customerId)
                .query(MAPPER).optional();
    }

    public Optional<Booking> findById(long id) {
        return jdbc.sql("SELECT * FROM bookings WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<Map<String, Object>> listForCustomer(long customerId) {
        return jdbc.sql("""
                        SELECT b.id, b.event_id AS "eventId", e.title AS "eventTitle", e.venue, e.starts_at AS "startsAt",
                               e.status AS "eventStatus", b.quantity, b.status, b.created_at AS "createdAt"
                        FROM bookings b JOIN events e ON e.id = b.event_id
                        WHERE b.customer_id = :c
                        ORDER BY b.created_at DESC""")
                .param("c", customerId).query().listOfRows();
    }

    public List<Map<String, Object>> listForEvent(long eventId) {
        return jdbc.sql("""
                        SELECT b.id, b.customer_id AS "customerId", u.name AS "customerName", u.email AS "customerEmail",
                               b.quantity, b.status, b.created_at AS "createdAt"
                        FROM bookings b JOIN users u ON u.id = b.customer_id
                        WHERE b.event_id = :e
                        ORDER BY b.created_at DESC
                        LIMIT 1000""")
                .param("e", eventId).query().listOfRows();
    }
}
