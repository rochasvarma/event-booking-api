package com.eventbooking.jobs;

import com.eventbooking.email.EmailService;
import com.eventbooking.email.EmailTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Background Task 1: Booking confirmation.
 * One job does everything: load the booking, send the email, record the in-app notification.
 * (An earlier version enqueued a second SEND_EMAIL job here. Merging them halves the background
 * DB work per booking, which matters when the worker shares a tiny CPU with the API.)
 *
 * Order matters for retries: the email is sent first and the notification is inserted only after
 * the send succeeded, so a failed send retries cleanly without duplicate notifications.
 */
@Component
public class BookingConfirmedHandler implements JobHandler {

    private final JdbcClient jdbc;
    private final EmailService email;

    public BookingConfirmedHandler(JdbcClient jdbc, EmailService email) {
        this.jdbc = jdbc;
        this.email = email;
    }

    @Override
    public String type() {
        return JobQueue.BOOKING_CONFIRMED;
    }

    @Override
    public void handle(JsonNode payload) throws Exception {
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

        // 1. Send the real email (throws on failure -> job retried with backoff).
        email.send((String) row.get("email"), subject, EmailTemplates.bookingConfirmation(row));

        // 2. Record the in-app notification.
        jdbc.sql("""
                        INSERT INTO notifications (user_id, event_id, type, title, message)
                        VALUES (:u, :e, 'BOOKING_CONFIRMED', :t, :m)""")
                .param("u", row.get("userId")).param("e", row.get("eventId")).param("t", subject)
                .param("m", "Your booking #" + bookingId + " for " + row.get("quantity") + " ticket(s) to \""
                        + row.get("title") + "\" is confirmed.")
                .update();
    }
}
