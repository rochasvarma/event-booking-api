package com.eventbooking.jobs;

import com.eventbooking.email.EmailService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Delivers one email. Failures throw, so the job is retried with exponential backoff. */
@Component
public class SendEmailHandler implements JobHandler {

    private final EmailService email;

    public SendEmailHandler(EmailService email) {
        this.email = email;
    }

    @Override
    public String type() {
        return JobQueue.SEND_EMAIL;
    }

    @Override
    public void handle(JsonNode p) throws Exception {
        email.send(p.get("to").asText(), p.get("subject").asText(), p.get("html").asText());
    }
}
