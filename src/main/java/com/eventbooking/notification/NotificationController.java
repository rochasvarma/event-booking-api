package com.eventbooking.notification;

import com.eventbooking.jobs.JobQueue;
import com.eventbooking.security.AuthUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class NotificationController {

    private final JdbcClient jdbc;
    private final JobQueue jobs;

    public NotificationController(JdbcClient jdbc, JobQueue jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    /** In-app notifications for the logged-in user (booking confirmations, event updates). */
    @GetMapping("/api/notifications/me")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal AuthUser user,
                                          @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return jdbc.sql("""
                        SELECT id, event_id AS "eventId", type, title, message, is_read AS "isRead", created_at AS "createdAt"
                        FROM notifications
                        WHERE user_id = :u AND (:all OR NOT is_read)
                        ORDER BY created_at DESC
                        LIMIT 100""")
                .param("u", user.id()).param("all", !unreadOnly)
                .query().listOfRows();
    }

    @PostMapping("/api/notifications/{id}/read")
    public Map<String, Object> markRead(@AuthenticationPrincipal AuthUser user, @PathVariable long id) {
        int n = jdbc.sql("UPDATE notifications SET is_read = true WHERE id = :id AND user_id = :u")
                .param("id", id).param("u", user.id()).update();
        return Map.of("updated", n);
    }

    /** Small ops endpoint so the demo can show emails/jobs being processed. Organizer-only. */
    @GetMapping("/api/organizer/ops/stats")
    @PreAuthorize("hasRole('ORGANIZER')")
    public Map<String, Object> stats() {
        List<Map<String, Object>> emails = jdbc.sql("""
                        SELECT status, count(*) AS n FROM email_log GROUP BY status""")
                .query().listOfRows();
        List<Map<String, Object>> recent = jdbc.sql("""
                        SELECT recipient, subject, status, provider, error, created_at AS "createdAt"
                        FROM email_log ORDER BY id DESC LIMIT 10""")
                .query().listOfRows();
        return Map.of("jobs", jobs.stats(), "emails", emails, "recentEmails", recent);
    }
}
