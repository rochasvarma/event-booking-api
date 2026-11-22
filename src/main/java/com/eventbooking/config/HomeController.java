package com.eventbooking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {
    private final String mode;
    private final boolean virtualThreads;
    private final int poolSize;

    public HomeController(@Value("${app.booking.mode}") String mode,
                          @Value("${spring.threads.virtual.enabled:false}") boolean virtualThreads,
                          @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        this.mode = mode;
        this.virtualThreads = virtualThreads;
        this.poolSize = poolSize;
    }

    /** Shows which performance profile is running - handy in the demo video. */
    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "event-booking-api",
                "bookingMode", mode,
                "virtualThreads", virtualThreads,
                "dbPoolSize", poolSize,
                "docs", "See README.md for the full API reference");
    }
}
