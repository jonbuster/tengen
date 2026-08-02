package com.tengencorp.tengen.service;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDestinationValidatorTest {

    private final WebhookDestinationValidator validator = new WebhookDestinationValidator();

    @Test
    void rejectsNonHttpsCredentialsAndLocalhost() {
        assertThatThrownBy(() -> validator.validateSyntax("http://example.com/hook"))
            .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> validator.validateSyntax("https://user:pass@example.com/hook"))
            .hasMessageContaining("credentials");
        assertThatThrownBy(() -> validator.validateSyntax("https://localhost/hook"))
            .hasMessageContaining("public host");
    }

    @Test
    void recognizesPrivateAndPublicAddresses() throws Exception {
        assertThat(WebhookDestinationValidator.isPublic(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(WebhookDestinationValidator.isPublic(InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(WebhookDestinationValidator.isPublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(WebhookDestinationValidator.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(WebhookDestinationValidator.isPublic(InetAddress.getByName("fc00::1"))).isFalse();
    }
}
