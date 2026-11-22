package com.eventbooking.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends real emails through either
 *  - Brevo's HTTPS API (default; works on hosts like Render that block outbound SMTP ports), or
 *  - plain SMTP via Spring's JavaMailSender (Gmail app password, Brevo SMTP relay, Mailtrap, ...).
 * Every attempt is written to email_log.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JdbcClient jdbc;
    private final JavaMailSender mailSender;
    private final RestClient brevo;
    private final String provider;
    private final String from;
    private final String fromName;
    private final Set<String> suppressDomains;

    public EmailService(JdbcClient jdbc, JavaMailSender mailSender,
                        @Value("${app.email.provider}") String provider,
                        @Value("${app.email.from}") String from,
                        @Value("${app.email.from-name}") String fromName,
                        @Value("${app.email.brevo-api-key}") String brevoApiKey,
                        @Value("${app.email.suppress-domains}") String suppressDomains) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.provider = provider.toLowerCase(Locale.ROOT);
        this.from = from;
        this.fromName = fromName;
        this.suppressDomains = Arrays.stream(suppressDomains.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        this.brevo = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("accept", "application/json")
                .build();
    }

    public void send(String to, String subject, String html) throws Exception {
        String domain = to.substring(to.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (suppressDomains.contains(domain)) {
            logRow(to, subject, "SUPPRESSED", null, null);
            return;
        }
        try {
            String messageId = switch (provider) {
                case "smtp" -> sendSmtp(to, subject, html);
                case "brevo-api" -> sendBrevo(to, subject, html);
                default -> throw new IllegalStateException("Unknown EMAIL_PROVIDER " + provider);
            };
            logRow(to, subject, "SENT", messageId, null);
            log.info("Email sent to {} via {} ({})", to, provider, subject);
        } catch (Exception e) {
            logRow(to, subject, "FAILED", null, e.getMessage());
            throw e; // let the job retry
        }
    }

    private String sendBrevo(String to, String subject, String html) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("email", from, "name", fromName),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", html);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = brevo.post().uri("/smtp/email")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return res == null ? null : String.valueOf(res.get("messageId"));
    }

    private String sendSmtp(String to, String subject, String html) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
        h.setFrom(from, fromName);
        h.setTo(to);
        h.setSubject(subject);
        h.setText(html, true);
        mailSender.send(msg);
        return msg.getMessageID();
    }

    private void logRow(String to, String subject, String status, String messageId, String error) {
        jdbc.sql("""
                        INSERT INTO email_log (recipient, subject, status, provider, provider_message_id, error)
                        VALUES (:to, :subject, :status, :provider, :mid, :err)""")
                .param("to", to).param("subject", subject).param("status", status)
                .param("provider", provider).param("mid", messageId).param("err", error)
                .update();
    }
}
