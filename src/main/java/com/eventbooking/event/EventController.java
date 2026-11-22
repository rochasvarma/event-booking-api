package com.eventbooking.event;

import com.eventbooking.booking.BookingRepository;
import com.eventbooking.common.ApiException;
import com.eventbooking.event.EventRepository.Event;
import com.eventbooking.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
public class EventController {

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 300) String venue,
            @NotNull @Future OffsetDateTime startsAt,
            @NotNull @Min(1) @Max(1_000_000) Integer totalTickets) {}

    public record UpdateEventRequest(
            @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @Size(max = 300) String venue,
            OffsetDateTime startsAt,
            @Min(1) @Max(1_000_000) Integer totalTickets) {}

    private final EventRepository events;
    private final EventService service;
    private final BookingRepository bookings;

    public EventController(EventRepository events, EventService service, BookingRepository bookings) {
        this.events = events;
        this.service = service;
        this.bookings = bookings;
    }

    // ---------- Public / customer browsing ----------

    @GetMapping("/api/events")
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 1) page = 1;
        size = Math.max(1, Math.min(size, 100));
        List<Event> items = events.listUpcoming(q, size, (page - 1) * size);
        return Map.of("page", page, "size", size, "items", items);
    }

    @GetMapping("/api/events/{id}")
    public Event get(@PathVariable long id) {
        return events.findById(id).orElseThrow(() -> ApiException.notFound("Event not found"));
    }

    // ---------- Organizer ----------

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Event create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CreateEventRequest r) {
        return service.create(user, new EventService.CreateEvent(r.title(), r.description(), r.venue(),
                r.startsAt(), r.totalTickets()));
    }

    @PatchMapping("/api/events/{id}")
    public Event update(@AuthenticationPrincipal AuthUser user, @PathVariable long id,
                        @Valid @RequestBody UpdateEventRequest r) {
        return service.update(user, id, new EventService.UpdateEvent(r.title(), r.description(), r.venue(),
                r.startsAt(), r.totalTickets()));
    }

    @DeleteMapping("/api/events/{id}")
    public Event cancel(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return service.cancel(user, id);
    }

    @GetMapping("/api/organizer/events")
    public List<Event> myEvents(@AuthenticationPrincipal AuthUser user) {
        return events.listByOrganizer(user.id());
    }

    @GetMapping("/api/organizer/events/{id}/bookings")
    public Map<String, Object> eventBookings(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        Event e = service.getOwned(user, id);
        return Map.of(
                "event", e,
                "ticketsSold", e.totalTickets() - e.availableTickets(),
                "bookings", bookings.listForEvent(id));
    }
}
