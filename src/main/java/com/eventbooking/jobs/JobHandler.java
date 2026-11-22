package com.eventbooking.jobs;

import com.fasterxml.jackson.databind.JsonNode;

public interface JobHandler {
    String type();

    /** Throw to trigger a retry with backoff. */
    void handle(JsonNode payload) throws Exception;
}
