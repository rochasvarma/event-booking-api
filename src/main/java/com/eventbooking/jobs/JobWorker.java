package com.eventbooking.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Polls the jobs table and runs handlers on a bounded thread pool.
 * Runs inside the web process by default (free hosting tiers have no separate worker);
 * set JOBS_ENABLED=false on web instances and true on a dedicated worker to split them.
 */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobQueue queue;
    private final ObjectMapper mapper;
    private final Map<String, JobHandler> handlers;
    private final ExecutorService pool;
    private final int batchSize;
    private final boolean enabled;

    public JobWorker(JobQueue queue, ObjectMapper mapper, List<JobHandler> handlerList,
                     @Value("${app.jobs.batch-size}") int batchSize,
                     @Value("${app.jobs.worker-threads}") int threads,
                     @Value("${app.jobs.enabled}") boolean enabled) {
        this.queue = queue;
        this.mapper = mapper;
        this.handlers = handlerList.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.pool = Executors.newFixedThreadPool(threads);
        this.batchSize = batchSize;
        this.enabled = enabled;
        log.info("Job worker enabled={} handlers={}", enabled, handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms}")
    public void poll() {
        if (!enabled) return;
        try {
            // Keep draining while full batches come back, then wait for the next tick.
            List<JobQueue.Job> jobs;
            do {
                jobs = queue.claim(batchSize);
                List<Future<?>> futures = new ArrayList<>();
                for (JobQueue.Job job : jobs) futures.add(pool.submit(() -> run(job)));
                for (Future<?> f : futures) f.get();
            } while (jobs.size() == batchSize);
        } catch (Exception e) {
            log.warn("Job poll failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverStuck() {
        if (!enabled) return;
        try {
            int n = queue.requeueStuck(300);
            if (n > 0) log.warn("Re-queued {} stuck jobs", n);
        } catch (Exception e) {
            log.warn("Stuck-job recovery failed: {}", e.getMessage());
        }
    }

    private void run(JobQueue.Job job) {
        JobHandler handler = handlers.get(job.type());
        try {
            if (handler == null) throw new IllegalStateException("No handler for " + job.type());
            handler.handle(mapper.readTree(job.payload()));
            queue.markDone(job.id());
        } catch (Exception e) {
            log.warn("Job {} ({}) attempt {}/{} failed: {}", job.id(), job.type(), job.attempts(),
                    job.maxAttempts(), e.getMessage());
            try {
                queue.markFailed(job, e.getMessage());
            } catch (Exception inner) {
                log.error("Could not record failure for job {}", job.id(), inner);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }
}
