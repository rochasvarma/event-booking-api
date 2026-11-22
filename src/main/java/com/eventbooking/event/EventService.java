package com.eventbooking.event;

import com.eventbooking.common.ApiException;
import com.eventbooking.event.EventRepository.Event;
import com.eventbooking.jobs.JobQueue;
import com.eventbooking.security.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EventService {

    public record CreateEvent(String title, String description, String venue, OffsetDateTime startsAt, int totalTickets) {}

    public record UpdateEvent(String title, String description, String venue, OffsetDateTime startsAt, Integer totalTickets) {}

    private final EventRepository events;
    private final JobQueue jobs;

    public EventService(EventRepository events, JobQueue jobs) {
        this.events = events;
        this.jobs = jobs;
    }

    public Event create(AuthUser organizer, CreateEvent c) {
        if (!c.startsAt().isAfter(OffsetDateTime.now())) throw ApiException.badRequest("startsAt must be in the future");
        return events.insert(organizer.id(), c.title().trim(), c.description(), c.venue().trim(), c.startsAt(), c.totalTickets());
    }

    /**
     * Update an event and, in the same transaction, enqueue an EVENT_UPDATED job that notifies
     * every customer holding a confirmed booking.
     */
    @Transactional
    public Event update(AuthUser organizer, long eventId, UpdateEvent u) {
        Event cur = loadOwnedForUpdate(organizer, eventId);
        if (!"ACTIVE".equals(cur.status())) throw ApiException.conflict("Cancelled events cannot be edited");

        List<String> changes = new ArrayList<>();
        String title = pick("Title", cur.title(), u.title() == null ? null : u.title().trim(), changes);
        String desc = pick("Description", cur.description(), u.description(), changes);
        String venue = pick("Venue", cur.venue(), u.venue() == null ? null : u.venue().trim(), changes);
        OffsetDateTime startsAt = cur.startsAt();
        if (u.startsAt() != null && !u.startsAt().isEqual(cur.startsAt())) {
            if (!u.startsAt().isAfter(OffsetDateTime.now())) throw ApiException.badRequest("startsAt must be in the future");
            changes.add("Date/time: " + cur.startsAt() + " → " + u.startsAt());
            startsAt = u.startsAt();
        }
        int total = cur.totalTickets();
        int available = cur.availableTickets();
        if (u.totalTickets() != null && u.totalTickets() != cur.totalTickets()) {
            int sold = cur.totalTickets() - cur.availableTickets();
            if (u.totalTickets() < sold) {
                throw ApiException.conflict("totalTickets cannot be below tickets already sold (" + sold + ")");
            }
            total = u.totalTickets();
            available = total - sold;
            // Capacity changes are not interesting to existing ticket holders; no customer-facing change line.
        }

        Event updated = events.update(new Event(cur.id(), cur.organizerId(), title, desc, venue, startsAt,
                total, available, cur.status(), cur.createdAt(), cur.updatedAt()));

        if (!changes.isEmpty()) {
            jobs.enqueue(JobQueue.EVENT_UPDATED, Map.of("eventId", eventId, "changes", changes, "cancelled", false));
        }
        return updated;
    }

    @Transactional
    public Event cancel(AuthUser organizer, long eventId) {
        Event cur = loadOwnedForUpdate(organizer, eventId);
        if ("CANCELLED".equals(cur.status())) return cur;
        Event updated = events.update(new Event(cur.id(), cur.organizerId(), cur.title(), cur.description(), cur.venue(),
                cur.startsAt(), cur.totalTickets(), cur.availableTickets(), "CANCELLED", cur.createdAt(), cur.updatedAt()));
        jobs.enqueue(JobQueue.EVENT_UPDATED,
                Map.of("eventId", eventId, "changes", List.of("The event has been cancelled"), "cancelled", true));
        return updated;
    }

    public Event getOwned(AuthUser organizer, long eventId) {
        Event e = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
        if (e.organizerId() != organizer.id()) throw ApiException.forbidden("You do not own this event");
        return e;
    }

    private Event loadOwnedForUpdate(AuthUser organizer, long eventId) {
        Event e = events.findByIdForUpdate(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
        if (e.organizerId() != organizer.id()) throw ApiException.forbidden("You do not own this event");
        return e;
    }

    private static String pick(String label, String cur, String next, List<String> changes) {
        if (next == null || Objects.equals(cur, next)) return cur;
        if (next.isBlank() && !"Description".equals(label)) throw ApiException.badRequest(label + " cannot be blank");
        changes.add(label + ": " + (cur == null ? "(none)" : cur) + " → " + next);
        return next;
    }
}
