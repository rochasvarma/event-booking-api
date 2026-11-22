package com.eventbooking.booking;

import com.eventbooking.booking.BookingRepository.Booking;
import com.eventbooking.common.ApiException;
import com.eventbooking.event.EventRepository;
import com.eventbooking.event.EventRepository.Event;
import com.eventbooking.jobs.JobQueue;
import com.eventbooking.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final EventRepository events;
    private final JobQueue jobs;
    private final boolean baseline;
    private final int maxPerBooking;

    public BookingService(BookingRepository bookings, EventRepository events, JobQueue jobs,
                          @Value("${app.booking.mode}") String mode,
                          @Value("${app.booking.max-tickets-per-booking}") int maxPerBooking) {
        this.bookings = bookings;
        this.events = events;
        this.jobs = jobs;
        this.baseline = "baseline".equalsIgnoreCase(mode);
        this.maxPerBooking = maxPerBooking;
    }

    @Transactional
    public Booking book(AuthUser customer, long eventId, int qty) {
        if (qty < 1 || qty > maxPerBooking) {
            throw ApiException.badRequest("quantity must be between 1 and " + maxPerBooking);
        }
        Booking booking = baseline ? bookBaseline(customer, eventId, qty) : bookOptimized(customer, eventId, qty);

        // Same transaction as the booking -> the confirmation email can't be lost (transactional outbox).
        jobs.enqueue(JobQueue.BOOKING_CONFIRMED, Map.of("bookingId", booking.id()));
        return booking;
    }

    /** One SQL statement; the event row lock is held only for that statement. */
    private Booking bookOptimized(AuthUser customer, long eventId, int qty) {
        return bookings.bookAtomically(eventId, customer.id(), qty)
                .orElseThrow(() -> explainFailure(eventId, qty));
    }

    /**
     * First-cut implementation: lock the event row, check in Java, write back, insert.
     * Correct (no overselling) but the row lock is held across 3 round trips + commit,
     * so all bookings for one event queue up behind each other and the DB pool drains fast.
     */
    private Booking bookBaseline(AuthUser customer, long eventId, int qty) {
        Event e = events.findByIdForUpdate(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
        validateBookable(e, qty);
        bookings.decrementAvailable(eventId, e.availableTickets() - qty);
        return bookings.insert(eventId, customer.id(), qty);
    }

    private ApiException explainFailure(long eventId, int qty) {
        Event e = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
        try {
            validateBookable(e, qty);
        } catch (ApiException ex) {
            return ex;
        }
        return ApiException.conflict("Tickets just sold out, please try again");
    }

    private static void validateBookable(Event e, int qty) {
        if (!"ACTIVE".equals(e.status())) throw ApiException.conflict("Event is cancelled");
        if (!e.startsAt().isAfter(OffsetDateTime.now())) throw ApiException.conflict("Event has already started");
        if (e.availableTickets() < qty) {
            throw ApiException.conflict(e.availableTickets() == 0 ? "Event is sold out"
                    : "Only " + e.availableTickets() + " tickets left");
        }
    }

    @Transactional
    public Booking cancel(AuthUser customer, long bookingId) {
        return bookings.cancel(bookingId, customer.id()).orElseThrow(() -> {
            Booking b = bookings.findById(bookingId).filter(x -> x.customerId() == customer.id())
                    .orElseThrow(() -> ApiException.notFound("Booking not found"));
            return ApiException.conflict("Booking is already " + b.status().toLowerCase());
        });
    }
}
