package com.eventbooking.email;

import org.springframework.web.util.HtmlUtils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public final class EmailTemplates {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm xxx");

    private EmailTemplates() {}

    private static String esc(Object o) {
        return o == null ? "" : HtmlUtils.htmlEscape(String.valueOf(o));
    }

    private static String date(Object o) {
        return o instanceof OffsetDateTime d ? d.format(FMT) : esc(o);
    }

    public static String bookingConfirmation(Map<String, Object> r) {
        return """
                <div style="font-family:Arial,sans-serif;max-width:560px">
                  <h2>Your booking is confirmed 🎟️</h2>
                  <p>Hi %s,</p>
                  <p>Thanks for booking! Here are your details:</p>
                  <table cellpadding="6" style="border-collapse:collapse">
                    <tr><td><b>Booking ID</b></td><td>#%s</td></tr>
                    <tr><td><b>Event</b></td><td>%s</td></tr>
                    <tr><td><b>Venue</b></td><td>%s</td></tr>
                    <tr><td><b>When</b></td><td>%s</td></tr>
                    <tr><td><b>Tickets</b></td><td>%s</td></tr>
                  </table>
                  <p>See you there!</p>
                </div>""".formatted(esc(r.get("name")), esc(r.get("bookingId")), esc(r.get("title")),
                esc(r.get("venue")), date(r.get("startsAt")), esc(r.get("quantity")));
    }

    /** {{name}} is substituted per recipient in SQL during fan-out. */
    public static String eventUpdate(String title, String venue, OffsetDateTime startsAt,
                                     List<String> changes, boolean cancelled) {
        StringBuilder li = new StringBuilder();
        for (String c : changes) li.append("<li>").append(esc(c)).append("</li>");
        return """
                <div style="font-family:Arial,sans-serif;max-width:560px">
                  <h2>%s</h2>
                  <p>Hi {{name}},</p>
                  <p>An event you have tickets for has %s:</p>
                  <p><b>%s</b><br>%s<br>%s</p>
                  <ul>%s</ul>
                  <p>You can view your bookings any time in the app.</p>
                </div>""".formatted(cancelled ? "Event cancelled" : "Event updated",
                cancelled ? "been cancelled" : "changed", esc(title), esc(venue), date(startsAt), li);
    }
}
