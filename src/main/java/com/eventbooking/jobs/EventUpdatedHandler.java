package com.eventbooking.jobs;

import com.eventbooking.email.EmailTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Background Task 2: Event update notification.
 * Fan-out is done set-based in SQL (no per-customer loop in Java):
 *   1. one INSERT ... SELECT creates an in-app notification for every distinct customer
 *      with a confirmed booking;
 *   2. one INSERT ... SELECT creates a SEND_EMAIL job per customer, so each email is retried
 *      independently and a single bad address can't block the rest.
 * Both happen in one transaction, so a retry of this job never produces duplicates.
 */
@Component
public class EventUpdatedHandler implements JobHandler {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public EventUpdatedHandler(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public String type() {
        return JobQueue.EVENT_UPDATED;
    }

    @Override
    public void handle(JsonNode payload) {
        long eventId = payload.get("eventId").asLong();
        boolean cancelled = payload.path("cancelled").asBoolean(false);
        List<String> changes = new ArrayList<>();
        payload.path("changes").forEach(n -> changes.add(n.asText()));

        record Ev(String title, String venue, OffsetDateTime startsAt) {}
        Ev ev = jdbc.sql("SELECT title, venue, starts_at FROM events WHERE id = :id").param("id", eventId)
                .query((rs, i) -> new Ev(rs.getString("title"), rs.getString("venue"),
                        rs.getObject("starts_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> new IllegalStateException("Event " + eventId + " not found"));

        String subject = (cancelled ? "Event cancelled: " : "Event updated: ") + ev.title();
        String message = String.join("; ", changes);
        String html = EmailTemplates.eventUpdate(ev.title(), ev.venue(), ev.startsAt(), changes, cancelled);

        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            INSERT INTO notifications (user_id, event_id, type, title, message)
                            SELECT DISTINCT b.customer_id, :e, :type, :t, :m
                            FROM bookings b
                            WHERE b.event_id = :e AND b.status = 'CONFIRMED'""")
                    .param("e", eventId).param("type", cancelled ? "EVENT_CANCELLED" : "EVENT_UPDATED")
                    .param("t", subject).param("m", message).update();

            jdbc.sql("""
                            INSERT INTO jobs (type, payload)
                            SELECT 'SEND_EMAIL', jsonb_build_object('to', u.email, 'subject', :t,
                                                                    'html', replace(:html, '{{name}}',
                                       replace(replace(replace(u.name, '&', '&amp;'), '<', '&lt;'), '>', '&gt;')))
                            FROM (SELECT DISTINCT customer_id FROM bookings
                                  WHERE event_id = :e AND status = 'CONFIRMED') c
                            JOIN users u ON u.id = c.customer_id""")
                    .param("e", eventId).param("t", subject).param("html", html).update();
        });
    }
}
