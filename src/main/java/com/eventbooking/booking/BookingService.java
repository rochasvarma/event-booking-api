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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class BookingService {

    private final BookingRepository bookings;
    private final EventRepository events;
    private final JobQueue jobs;
    private final TransactionTemplate tx;
    private final boolean baseline;
    private final int maxPerBooking;

    public BookingService(BookingRepository bookings, EventRepository events, JobQueue jobs, TransactionTemplate tx,
                          @Value("${app.booking.mode}") String mode,
                          @Value("${app.booking.max-tickets-per-booking}") int maxPerBooking) {
        this.bookings = bookings;
        this.events = events;
        this.jobs = jobs;
        this.tx = tx;
        this.baseline = "baseline".equalsIgnoreCase(mode);
        this.maxPerBooking = maxPerBooking;
    }

    public Booking book(AuthUser customer, long eventId, int qty) {
        if (qty < 1 || qty > maxPerBooking) {
            throw ApiException.badRequest("quantity must be between 1 and " + maxPerBooking);
        }
        return baseline ? bookBaseline(customer, eventId, qty) : bookOptimized(customer, eventId, qty);
    }

    /**
     * Optimized: ONE auto-committed SQL statement reserves tickets, inserts the booking and
     * enqueues the confirmation job. No BEGIN/COMMIT round trips; the row lock lasts only
     * for the statement itself.
     */
    private Booking bookOptimized(AuthUser customer, long eventId, int qty) {
        return bookings.bookWithOutbox(eventId, customer.id(), qty)
                .orElseThrow(() -> explainFailure(eventId, qty));
    }

    /**
     * Baseline (first-cut implementation): explicit transaction, lock the event row, check in
     * Java, write back, insert booking, insert job, commit. Correct (no overselling) but the row
     * lock is held across 5 round trips, so bookings for one event queue up behind each other
     * and the DB pool drains fast.
     */
    private Booking bookBaseline(AuthUser customer, long eventId, int qty) {
        return tx.execute(status -> {
            Event e = events.findByIdForUpdate(eventId).orElseThrow(() -> ApiException.notFound("Event not found"));
            validateBookable(e, qty);
            bookings.decrementAvailable(eventId, e.availableTickets() - qty);
            Booking b = bookings.insert(eventId, customer.id(), qty);
            jobs.enqueue(JobQueue.BOOKING_CONFIRMED, Map.of("bookingId", b.id()));
            return b;
        });
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
