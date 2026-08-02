package com.tengencorp.tengen.service;

import com.tengencorp.tengen.config.WebhookDeliveryProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookClientTest {

    @Test
    void signatureIsStableForThePersistedTimestampAndBody() {
        WebhookDeliveryProperties properties = new WebhookDeliveryProperties();
        properties.setSigningSecret("01234567890123456789012345678901");
        WebhookClient client = new WebhookClient(
            new ObjectMapper(), properties, new WebhookDestinationValidator());

        String first = client.signature("1785715200", "{\"ok\":true}");
        String retry = client.signature("1785715200", "{\"ok\":true}");

        assertThat(first).isEqualTo(retry)
            .startsWith("v1=")
            .hasSize(67);
    }
}
