package com.eventbooking.jobs;

import com.eventbooking.email.EmailTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Background Task 1: Booking confirmation.
 * Creates an in-app notification and enqueues a SEND_EMAIL job (one transaction), so the actual
 * email delivery gets its own independent retries.
 */
@Component
public class BookingConfirmedHandler implements JobHandler {

    private final JdbcClient jdbc;
    private final JobQueue queue;
    private final TransactionTemplate tx;

    public BookingConfirmedHandler(JdbcClient jdbc, JobQueue queue, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.tx = tx;
    }

    @Override
    public String type() {
        return JobQueue.BOOKING_CONFIRMED;
    }

    @Override
    public void handle(JsonNode payload) {
        long bookingId = payload.get("bookingId").asLong();
        var row = jdbc.sql("""
                        SELECT b.id, b.quantity, b.event_id, u.id AS user_id, u.name, u.email,
                               e.title, e.venue, e.starts_at
                        FROM bookings b
                        JOIN users u ON u.id = b.customer_id
                        JOIN events e ON e.id = b.event_id
                        WHERE b.id = :id""")
                .param("id", bookingId)
                .query((rs, i) -> Map.<String, Object>of(
                        "bookingId", rs.getLong("id"),
                        "quantity", rs.getInt("quantity"),
                        "eventId", rs.getLong("event_id"),
                        "userId", rs.getLong("user_id"),
                        "name", rs.getString("name"),
                        "email", rs.getString("email"),
                        "title", rs.getString("title"),
                        "venue", rs.getString("venue"),
                        "startsAt", rs.getObject("starts_at", OffsetDateTime.class)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " not found"));

        String subject = "Booking confirmed: " + row.get("title");
        String html = EmailTemplates.bookingConfirmation(row);
        String message = "Your booking #" + bookingId + " for " + row.get("quantity") + " ticket(s) to \""
                + row.get("title") + "\" is confirmed.";

        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            INSERT INTO notifications (user_id, event_id, type, title, message)
                            VALUES (:u, :e, 'BOOKING_CONFIRMED', :t, :m)""")
                    .param("u", row.get("userId")).param("e", row.get("eventId"))
                    .param("t", subject).param("m", message).update();
            queue.enqueue(JobQueue.SEND_EMAIL, Map.of("to", row.get("email"), "subject", subject, "html", html));
        });
    }
}
