package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationRecipientMode;
import com.tengencorp.tengen.entity.Rule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves and validates notification recipients without evaluating arbitrary expressions. */
@Service
public class NotificationRecipientResolver {

    private static final Pattern EMAIL = Pattern.compile(
        "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    public List<String> resolve(Rule rule, Event event, NotificationChannel channel) {
        List<String> values = new ArrayList<>();
        if (rule.getNotificationRecipientMode() == NotificationRecipientMode.EVENT_FIELD) {
            String field = rule.getNotificationRecipientField();
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("Notification recipient field is required");
            }
            Object value = lookup(event.getData(), field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("Notification recipient field is missing: " + field);
            }
            values.add(String.valueOf(value).trim());
        } else {
            if (rule.getNotificationRecipients() != null) {
                rule.getNotificationRecipients().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(values::add);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one notification recipient is required");
        }
        for (String value : values) {
            if (channel == NotificationChannel.EMAIL && !EMAIL.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid email recipient");
            }
            if (channel == NotificationChannel.SMS && !E164.matcher(value).matches()) {
                throw new IllegalArgumentException("SMS recipients must use E.164 format");
            }
        }
        if (channel == NotificationChannel.SMS && values.size() > 1) {
            throw new IllegalArgumentException("SMS rules support one recipient per notification");
        }
        return List.copyOf(values);
    }

    private Object lookup(Map<String, Object> data, String field) {
        String path = field.trim();
        if (path.startsWith("data.")) {
            path = path.substring("data.".length());
        }
        Object current = data;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> values)) {
                return null;
            }
            current = values.get(part);
        }
        return current;
    }
}
