package com.eventbooking.booking;

import com.eventbooking.booking.BookingRepository.Booking;
import com.eventbooking.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class BookingController {

    public record BookRequest(@NotNull @Min(1) Integer quantity) {}

    private final BookingService service;
    private final BookingRepository bookings;

    public BookingController(BookingService service, BookingRepository bookings) {
        this.service = service;
        this.bookings = bookings;
    }

    @PostMapping("/api/events/{eventId}/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public Booking book(@AuthenticationPrincipal AuthUser user, @PathVariable long eventId,
                        @Valid @RequestBody BookRequest r) {
        return service.book(user, eventId, r.quantity());
    }

    @GetMapping("/api/bookings/me")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal AuthUser user) {
        return bookings.listForCustomer(user.id());
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public Booking cancel(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        return service.cancel(user, id);
    }
}
