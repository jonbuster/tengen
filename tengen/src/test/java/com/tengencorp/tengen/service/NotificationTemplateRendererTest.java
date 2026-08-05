package com.tengencorp.tengen.service;

import com.tengencorp.tengen.entity.Event;
import com.tengencorp.tengen.entity.NotificationChannel;
import com.tengencorp.tengen.entity.NotificationTemplate;
import com.tengencorp.tengen.entity.Rule;
import com.tengencorp.tengen.entity.RuleAction;
import com.tengencorp.tengen.entity.RuleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateRendererTest {

    private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

    @Test
    void rendersNestedDataAndEscapesHtmlValues() {
        NotificationTemplate template = template(NotificationChannel.EMAIL);
        template.setSubjectTemplate("Welcome {{data.first_name}}");
        template.setTextTemplate("Hello {{data.first_name}}");
        template.setHtmlTemplate("<p>Hello {{data.first_name}}</p>");
        template.setCssTemplate("p { color: red; }");

        Event event = new Event("user.created", "accounts", Instant.parse("2026-08-05T00:00:00Z"),
            Map.of("first_name", "Ana <admin>"));
        Rule rule = rule();

        var rendered = renderer.render(template, event, rule, null);

        assertThat(rendered.subject()).isEqualTo("Welcome Ana <admin>");
        assertThat(rendered.textBody()).isEqualTo("Hello Ana <admin>");
        assertThat(rendered.htmlBody()).contains("Ana &lt;admin&gt;").contains("<style>p { color: red; }</style>");
    }

    @Test
    void rejectsMissingValuesAndUnsafeMarkup() {
        NotificationTemplate missing = template(NotificationChannel.EMAIL);
        missing.setSubjectTemplate("Welcome {{data.first_name}}");
        missing.setTextTemplate("Hello {{data.last_name}}");

        assertThatThrownBy(() -> renderer.render(
            missing,
            new Event("user.created", "accounts", Instant.now(), Map.of("first_name", "Ana")),
            rule(),
            null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("data.last_name");

        NotificationTemplate unsafe = template(NotificationChannel.EMAIL);
        unsafe.setSubjectTemplate("Subject");
        unsafe.setTextTemplate("Text");
        unsafe.setHtmlTemplate("<script>alert(1)</script>");
        assertThatThrownBy(() -> renderer.validate(unsafe))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafe");
    }

    @Test
    void smsDoesNotAllowHtmlOrCss() {
        NotificationTemplate template = template(NotificationChannel.SMS);
        template.setTextTemplate("Hello {{data.first_name}}");
        template.setHtmlTemplate("<p>Hello</p>");

        assertThatThrownBy(() -> renderer.validate(template))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SMS");
    }

    private NotificationTemplate template(NotificationChannel channel) {
        NotificationTemplate template = new NotificationTemplate();
        template.setChannel(channel);
        template.setName("template");
        template.setTextTemplate("Text");
        return template;
    }

    private Rule rule() {
        Rule rule = new Rule();
        rule.setName("Welcome");
        rule.setRuleType(RuleType.CONDITION);
        rule.setAction(RuleAction.EMAIL);
        rule.setRevision(1);
        return rule;
    }
}
