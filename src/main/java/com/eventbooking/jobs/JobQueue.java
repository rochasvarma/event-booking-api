package com.eventbooking.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Postgres-backed durable job queue.
 *
 * enqueue() joins the caller's transaction (JdbcClient uses the Spring-managed connection),
 * so the job row commits atomically with the booking / event change: the transactional
 * outbox pattern. Workers claim jobs with FOR UPDATE SKIP LOCKED, so any number of
 * app instances can process the queue concurrently without double-processing.
 */
@Component
public class JobQueue {

    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";
    public static final String EVENT_UPDATED = "EVENT_UPDATED";
    public static final String SEND_EMAIL = "SEND_EMAIL";

    public record Job(long id, String type, String payload, int attempts, int maxAttempts) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JobQueue(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void enqueue(String type, Map<String, ?> payload) {
        jdbc.sql("INSERT INTO jobs (type, payload) VALUES (:type, CAST(:payload AS jsonb))")
                .param("type", type)
                .param("payload", toJson(payload))
                .update();
    }

    /** Atomically claim up to {@code limit} due jobs. */
    public List<Job> claim(int limit) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'RUNNING', locked_at = now(), attempts = attempts + 1, updated_at = now()
                        WHERE id IN (
                            SELECT id FROM jobs
                            WHERE status = 'PENDING' AND run_at <= now()
                            ORDER BY run_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, type, payload::text AS payload, attempts, max_attempts""")
                .param("limit", limit)
                .query((rs, i) -> new Job(rs.getLong("id"), rs.getString("type"), rs.getString("payload"),
                        rs.getInt("attempts"), rs.getInt("max_attempts")))
                .list();
    }

    public void markDone(long id) {
        jdbc.sql("UPDATE jobs SET status = 'DONE', locked_at = NULL, last_error = NULL, updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    /** Retry with exponential backoff (2^attempts seconds) or give up after max_attempts. */
    public void markFailed(Job job, String error) {
        boolean giveUp = job.attempts() >= job.maxAttempts();
        jdbc.sql("""
                        UPDATE jobs SET status = :status, locked_at = NULL, last_error = :err, updated_at = now(),
                               run_at = now() + make_interval(secs => :delay)
                        WHERE id = :id""")
                .param("status", giveUp ? "FAILED" : "PENDING")
                .param("err", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 2000)))
                .param("delay", Math.pow(2, job.attempts()))
                .param("id", job.id())
                .update();
    }

    /** Jobs stuck in RUNNING (e.g. the instance crashed mid-job) go back to PENDING. */
    public int requeueStuck(int olderThanSeconds) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'PENDING', locked_at = NULL, updated_at = now()
                        WHERE status = 'RUNNING' AND locked_at < now() - make_interval(secs => :s)""")
                .param("s", olderThanSeconds)
                .update();
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM jobs GROUP BY status")
                .query((org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> out.put(rs.getString("status"), rs.getLong("n")));
        return out;
    }

    public String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
