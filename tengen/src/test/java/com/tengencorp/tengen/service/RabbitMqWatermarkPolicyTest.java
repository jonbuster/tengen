package com.tengencorp.tengen.service;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqWatermarkPolicyTest {

    @Test
    void missingHeaderKeepsWatermarkingEnabled() {
        assertThat(RabbitMqWatermarkPolicy.shouldApply(new MessageProperties())).isTrue();
    }

    @Test
    void explicitBooleanFalseDisablesWatermarking() {
        MessageProperties properties = propertiesWith(false);

        assertThat(RabbitMqWatermarkPolicy.shouldApply(properties)).isFalse();
    }

    @Test
    void explicitStringAndBytesFalseDisableWatermarking() {
        MessageProperties stringProperties = propertiesWith(" false ");
        MessageProperties bytesProperties = propertiesWith(
            "false".getBytes(StandardCharsets.UTF_8));

        assertThat(RabbitMqWatermarkPolicy.shouldApply(stringProperties)).isFalse();
        assertThat(RabbitMqWatermarkPolicy.shouldApply(bytesProperties)).isFalse();
    }

    @Test
    void trueAndMalformedValuesFailSafeToWatermarking() {
        MessageProperties trueProperties = propertiesWith(true);
        MessageProperties malformedProperties = propertiesWith("no");
        MessageProperties unsupportedProperties = propertiesWith(0);

        assertThat(RabbitMqWatermarkPolicy.shouldApply(trueProperties)).isTrue();
        assertThat(RabbitMqWatermarkPolicy.shouldApply(malformedProperties)).isTrue();
        assertThat(RabbitMqWatermarkPolicy.shouldApply(unsupportedProperties)).isTrue();
    }

    private MessageProperties propertiesWith(Object value) {
        MessageProperties properties = new MessageProperties();
        properties.getHeaders().put(RabbitMqWatermarkPolicy.HEADER_NAME, value);
        return properties;
    }
}
