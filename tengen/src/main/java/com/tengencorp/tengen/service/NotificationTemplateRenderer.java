package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationTemplate;
import com.tengencorp.tengen.entity.Rule;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, intentionally small template renderer for notification content. */
@Service
public class NotificationTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile(
        "\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*}}"
    );
    private static final Pattern UNSAFE_HTML = Pattern.compile(
        "(?is)<\\s*(script|iframe|object|embed|form|meta|link)|\\bon[a-z0-9_-]+\\s*=|javascript:|data:text/html"
    );
    private static final Pattern UNSAFE_CSS = Pattern.compile(
        "(?is)(@import|url\\s*\\(|expression\\s*\\(|javascript:|behavior\\s*:|<\\s*/?\\s*style)"
    );

    public void validate(NotificationTemplate template) {
        if (template.getChannel() == null) {
            throw new IllegalArgumentException("Template channel is required");
        }
        validateSyntax(template.getSubjectTemplate(), "Subject template");
        validateSyntax(template.getTextTemplate(), "Text template");
        validateSyntax(template.getHtmlTemplate(), "HTML template");
        if (template.getCssTemplate() != null && !template.getCssTemplate().isBlank()) {
            if (PLACEHOLDER.matcher(template.getCssTemplate()).find()) {
                throw new IllegalArgumentException("CSS templates cannot contain dynamic placeholders");
            }
            validateCss(template.getCssTemplate());
        }
        if (template.getChannel() == NotificationChannel.EMAIL) {
            if (template.getSubjectTemplate() == null || template.getSubjectTemplate().isBlank()) {
                throw new IllegalArgumentException("Email subject template is required");
            }
            validateHtml(template.getHtmlTemplate());
        } else {
            if (template.getHtmlTemplate() != null && !template.getHtmlTemplate().isBlank()) {
                throw new IllegalArgumentException("SMS templates cannot contain HTML");
            }
            if (template.getCssTemplate() != null && !template.getCssTemplate().isBlank()) {
                throw new IllegalArgumentException("SMS templates cannot contain CSS");
            }
            if (template.getSubjectTemplate() != null && !template.getSubjectTemplate().isBlank()) {
                throw new IllegalArgumentException("SMS templates cannot contain a subject");
            }
        }
    }

    public RenderedContent render(NotificationTemplate template, Event event, Rule rule,
                                  String groupKey) {
        validate(template);
        Map<String, Object> context = context(event, rule, groupKey);
        String subject = template.getSubjectTemplate() == null ? null
            : renderText(template.getSubjectTemplate(), context, "Subject", false);
        String text = renderText(template.getTextTemplate(), context, "Text", false);
        String html = template.getHtmlTemplate() == null || template.getHtmlTemplate().isBlank()
            ? null : renderText(template.getHtmlTemplate(), context, "HTML", true);
        if (html != null && template.getCssTemplate() != null && !template.getCssTemplate().isBlank()) {
            html = "<style>" + template.getCssTemplate().trim() + "</style>" + html;
        }
        return new RenderedContent(subject, text, html);
    }

    private Map<String, Object> context(Event event, Rule rule, String groupKey) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("data", event.getData());
        Map<String, Object> eventContext = new LinkedHashMap<>();
        eventContext.put("id", event.getId());
        eventContext.put("type", event.getType());
        eventContext.put("source", event.getSource());
        eventContext.put("timestamp",
            event.getOccurredAt() != null ? event.getOccurredAt().toString() : null);
        context.put("event", eventContext);
        Map<String, Object> ruleContext = new LinkedHashMap<>();
        ruleContext.put("name", rule.getName());
        ruleContext.put("revision", rule.getEffectiveRevision());
        context.put("rule", ruleContext);
        Map<String, Object> matchContext = new LinkedHashMap<>();
        matchContext.put("groupKey", groupKey);
        context.put("match", matchContext);
        return context;
    }

    private String renderText(String template, Map<String, Object> context,
                              String label, boolean htmlEscapeValues) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = lookup(context, matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException(
                    label + " template variable is missing: " + matcher.group(1));
            }
            String replacement = String.valueOf(value);
            replacement = htmlEscapeValues ? escapeHtml(replacement) : replacement;
            if ("Subject".equals(label)) {
                replacement = replacement.replaceAll("[\\r\\n]+", " ");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        String rendered = result.toString();
        if (rendered.contains("{{") || rendered.contains("}}")) {
            throw new IllegalArgumentException(label + " template contains unsupported placeholder syntax");
        }
        return rendered;
    }

    private Object lookup(Map<String, Object> context, String path) {
        String[] parts = path.split("\\.");
        Object current = context;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> values) || !values.containsKey(part)) {
                return null;
            }
            current = values.get(part);
        }
        return current;
    }

    private void validateSyntax(String value, String label) {
        if (value == null || value.isBlank()) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(value);
        String withoutPlaceholders = matcher.replaceAll("");
        if (withoutPlaceholders.contains("{{") || withoutPlaceholders.contains("}}")) {
            throw new IllegalArgumentException(label + " contains unsupported placeholder syntax");
        }
        matcher.reset();
        while (matcher.find()) {
            String root = matcher.group(1).split("\\.")[0];
            if (!(root.equals("data") || root.equals("event")
                    || root.equals("rule") || root.equals("match"))) {
                throw new IllegalArgumentException(
                    label + " variable must use data.*, event.*, rule.*, or match.*");
            }
        }
    }

    private void validateHtml(String html) {
        if (html != null && UNSAFE_HTML.matcher(html).find()) {
            throw new IllegalArgumentException(
                "HTML template contains unsafe tags, event handlers, or URLs");
        }
    }

    private void validateCss(String css) {
        if (UNSAFE_CSS.matcher(css).find()) {
            throw new IllegalArgumentException(
                "CSS template contains unsafe imports, URLs, expressions, or markup");
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record RenderedContent(String subject, String textBody, String htmlBody) {
    }
}
