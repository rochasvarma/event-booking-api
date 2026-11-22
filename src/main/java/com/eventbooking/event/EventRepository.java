package com.eventbooking.event;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class EventRepository {

    public record Event(long id, long organizerId, String title, String description, String venue,
                        OffsetDateTime startsAt, int totalTickets, int availableTickets, String status,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    static final RowMapper<Event> MAPPER = (rs, i) -> new Event(
            rs.getLong("id"), rs.getLong("organizer_id"), rs.getString("title"), rs.getString("description"),
            rs.getString("venue"), rs.getObject("starts_at", OffsetDateTime.class),
            rs.getInt("total_tickets"), rs.getInt("available_tickets"), rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Event insert(long organizerId, String title, String description, String venue,
                        OffsetDateTime startsAt, int totalTickets) {
        return jdbc.sql("""
                        INSERT INTO events (organizer_id, title, description, venue, starts_at, total_tickets, available_tickets)
                        VALUES (:org, :title, :desc, :venue, :startsAt, :total, :total)
                        RETURNING *""")
                .param("org", organizerId).param("title", title).param("desc", description)
                .param("venue", venue).param("startsAt", startsAt).param("total", totalTickets)
                .query(MAPPER).single();
    }

    public Optional<Event> findById(long id) {
        return jdbc.sql("SELECT * FROM events WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Event> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT * FROM events WHERE id = :id FOR UPDATE").param("id", id).query(MAPPER).optional();
    }

    /** Public listing: active, upcoming events, optional text search, paginated. */
    public List<Event> listUpcoming(String q, int limit, int offset) {
        String pattern = (q == null || q.isBlank()) ? null : "%" + q.trim() + "%";
        return jdbc.sql("""
                        SELECT * FROM events
                        WHERE status = 'ACTIVE' AND starts_at > now()
                          AND (CAST(:q AS text) IS NULL OR title ILIKE :q OR venue ILIKE :q)
                        ORDER BY starts_at, id
                        LIMIT :limit OFFSET :offset""")
                .param("q", pattern).param("limit", limit).param("offset", offset)
                .query(MAPPER).list();
    }

    public List<Event> listByOrganizer(long organizerId) {
        return jdbc.sql("SELECT * FROM events WHERE organizer_id = :org ORDER BY starts_at DESC")
                .param("org", organizerId).query(MAPPER).list();
    }

    public Event update(Event e) {
        return jdbc.sql("""
                        UPDATE events SET title = :title, description = :desc, venue = :venue, starts_at = :startsAt,
                               total_tickets = :total, available_tickets = :available, status = :status, updated_at = now()
                        WHERE id = :id
                        RETURNING *""")
                .param("title", e.title()).param("desc", e.description()).param("venue", e.venue())
                .param("startsAt", e.startsAt()).param("total", e.totalTickets())
                .param("available", e.availableTickets()).param("status", e.status()).param("id", e.id())
                .query(MAPPER).single();
    }
}
