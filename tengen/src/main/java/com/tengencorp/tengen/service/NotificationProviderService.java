package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationDestination;
import com.tengencorp.tengen.entity.NotificationOutbox;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import jakarta.mail.Transport;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Initial concrete provider boundary: SMTP/SES SMTP for email and Twilio for SMS. */
@Service
public class NotificationProviderService {

    private static final Pattern TWILIO_SID = Pattern.compile("\\\"sid\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final NotificationSecretService secretService;
    private final RestClient twilioClient;

    public NotificationProviderService(NotificationSecretService secretService) {
        this.secretService = secretService;
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.twilioClient = RestClient.builder()
            .baseUrl("https://api.twilio.com")
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient))
            .build();
    }

    public NotificationProviderResult test(NotificationDestination destination) {
        try {
            Map<String, String> credentials = secretService.decrypt(destination);
            return switch (destination.getChannel()) {
                case EMAIL -> testEmail(destination, credentials);
                case SMS -> testSms(destination, credentials);
            };
        } catch (IllegalArgumentException exception) {
            return NotificationProviderResult.failure(false, "CONFIGURATION", safeMessage(exception));
        } catch (Exception exception) {
            return NotificationProviderResult.failure(true, "PROVIDER_UNAVAILABLE", safeMessage(exception));
        }
    }

    public NotificationProviderResult submit(NotificationDestination destination,
                                             NotificationOutbox outbox) {
        try {
            Map<String, String> credentials = secretService.decrypt(destination);
            return switch (destination.getChannel()) {
                case EMAIL -> submitEmail(destination, credentials, outbox);
                case SMS -> submitSms(destination, credentials, outbox);
            };
        } catch (IllegalArgumentException exception) {
            return NotificationProviderResult.failure(false, "CONFIGURATION", safeMessage(exception));
        } catch (Exception exception) {
            return NotificationProviderResult.failure(true, "PROVIDER_UNAVAILABLE", safeMessage(exception));
        }
    }

    private NotificationProviderResult testEmail(NotificationDestination destination,
                                                  Map<String, String> credentials) {
        if (!(destination.getProvider().equals("SMTP")
                || destination.getProvider().equals("AMAZON_SES_SMTP"))) {
            return NotificationProviderResult.failure(false, "PROVIDER_UNSUPPORTED",
                "Email provider must be SMTP or AMAZON_SES_SMTP");
        }
        try {
            JavaMailSenderImpl sender = mailSender(destination, credentials);
            Transport transport = sender.getSession().getTransport("smtp");
            transport.connect(sender.getHost(), sender.getPort(), sender.getUsername(), sender.getPassword());
            transport.close();
            return NotificationProviderResult.success(null);
        } catch (Exception exception) {
            return NotificationProviderResult.failure(isTransient(exception), "SMTP_CONNECTION",
                safeMessage(exception));
        }
    }

    private NotificationProviderResult submitEmail(NotificationDestination destination,
                                                    Map<String, String> credentials,
                                                    NotificationOutbox outbox) {
        if (!(destination.getProvider().equals("SMTP")
                || destination.getProvider().equals("AMAZON_SES_SMTP"))) {
            return NotificationProviderResult.failure(false, "PROVIDER_UNSUPPORTED",
                "Email provider must be SMTP or AMAZON_SES_SMTP");
        }
        try {
            Map<String, Object> message = outbox.getMessageSnapshot();
            List<String> recipients = stringList(message.get("recipients"));
            String subject = stringValue(message.get("subject"));
            String textBody = stringValue(message.get("textBody"));
            String htmlBody = stringValue(message.get("htmlBody"));
            JavaMailSenderImpl sender = mailSender(destination, credentials);
            var mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setTo(recipients.toArray(String[]::new));
            Map<String, Object> configuration = destination.getConfiguration();
            helper.setFrom(required(configuration, "fromAddress"),
                stringValue(configuration.getOrDefault("fromName", "")));
            String replyTo = stringValue(configuration.get("replyTo"));
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            if (htmlBody != null && !htmlBody.isBlank()) {
                helper.setText(textBody, htmlBody);
            } else {
                helper.setText(textBody);
            }
            sender.send(mimeMessage);
            return NotificationProviderResult.success(null);
        } catch (Exception exception) {
            return NotificationProviderResult.failure(isTransient(exception), "SMTP_SUBMIT",
                safeMessage(exception));
        }
    }

    private NotificationProviderResult testSms(NotificationDestination destination,
                                                Map<String, String> credentials) {
        if (!destination.getProvider().equals("TWILIO")) {
            return NotificationProviderResult.failure(false, "PROVIDER_UNSUPPORTED",
                "SMS provider must be TWILIO");
        }
        String accountSid = required(credentials, "accountSid");
        String token = required(credentials, "authToken");
        try {
            twilioClient.get()
                .uri("/2010-04-01/Accounts/{sid}.json", accountSid)
                .headers(headers -> headers.setBasicAuth(accountSid, token))
                .retrieve()
                .toBodilessEntity();
            return NotificationProviderResult.success(null);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return NotificationProviderResult.failure(status == 408 || status == 429 || status >= 500,
                "TWILIO_CONNECTION", "Twilio returned HTTP " + status);
        } catch (Exception exception) {
            return NotificationProviderResult.failure(true, "TWILIO_CONNECTION", safeMessage(exception));
        }
    }

    private NotificationProviderResult submitSms(NotificationDestination destination,
                                                  Map<String, String> credentials,
                                                  NotificationOutbox outbox) {
        if (!destination.getProvider().equals("TWILIO")) {
            return NotificationProviderResult.failure(false, "PROVIDER_UNSUPPORTED",
                "SMS provider must be TWILIO");
        }
        String accountSid = required(credentials, "accountSid");
        String token = required(credentials, "authToken");
        Map<String, Object> message = outbox.getMessageSnapshot();
        List<String> recipients = stringList(message.get("recipients"));
        if (recipients.size() != 1) {
            return NotificationProviderResult.failure(false, "RECIPIENT_POLICY",
                "Twilio SMS delivery requires exactly one recipient");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", recipients.getFirst());
        form.add("From", required(destination.getConfiguration(), "fromNumber"));
        form.add("Body", required(message, "body"));
        try {
            String body = twilioClient.post()
                .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                .headers(headers -> headers.setBasicAuth(accountSid, token))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
            String sid = extractTwilioSid(body);
            return NotificationProviderResult.success(sid);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            return NotificationProviderResult.failure(status == 408 || status == 429 || status >= 500,
                "TWILIO_SUBMIT", "Twilio returned HTTP " + status);
        } catch (Exception exception) {
            return NotificationProviderResult.failure(true, "TWILIO_SUBMIT", safeMessage(exception));
        }
    }

    private JavaMailSenderImpl mailSender(NotificationDestination destination,
                                           Map<String, String> credentials) {
        Map<String, Object> configuration = destination.getConfiguration();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(required(configuration, "host"));
        sender.setPort(integer(configuration, "port", 587));
        sender.setUsername(required(credentials, "username"));
        sender.setPassword(required(credentials, "password"));
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "5000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        String tlsMode = stringValue(configuration.getOrDefault("tlsMode", "STARTTLS"));
        if ("STARTTLS".equalsIgnoreCase(tlsMode)) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        } else if ("SSL".equalsIgnoreCase(tlsMode)) {
            properties.put("mail.smtp.ssl.enable", "true");
        }
        return sender;
    }

    private String extractTwilioSid(String response) {
        if (response == null) {
            return null;
        }
        Matcher matcher = TWILIO_SID.matcher(response);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Rendered recipients are missing");
        }
        return values.stream().map(this::stringValue).toList();
    }

    private String required(Map<String, ?> values, String key) {
        String value = stringValue(values.get(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider setting is required: " + key);
        }
        return value.trim();
    }

    private int integer(Map<String, ?> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (parsed < 1 || parsed > 65_535) {
                throw new IllegalArgumentException("Provider setting must be a valid port: " + key);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Provider setting must be a valid integer: " + key);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isTransient(Exception exception) {
        String message = safeMessage(exception).toLowerCase();
        return message.contains("timeout") || message.contains("temporarily")
            || message.contains("connection") || message.contains("refused");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String sanitized = message.replaceAll("(?i)(password|token|secret|authorization)[^,; ]*", "$1=[redacted]");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }
}
