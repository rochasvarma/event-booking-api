package com.eventbooking.security;

/** The authenticated principal, built from JWT claims. */
public record AuthUser(long id, String email, String name, String role) {
    public boolean isOrganizer() { return "ORGANIZER".equals(role); }
    public boolean isCustomer() { return "CUSTOMER".equals(role); }
}
