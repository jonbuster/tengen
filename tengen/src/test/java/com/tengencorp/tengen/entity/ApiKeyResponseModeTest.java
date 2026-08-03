package com.tengencorp.tengen.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyResponseModeTest {

    @Test
    void newKeysDefaultToCompactResponses() {
        ApiKey key = new ApiKey();

        assertThat(key.getEffectiveResponseMode()).isEqualTo(ResponseMode.COMPACT);
    }

    @Test
    void fullModeRemainsAvailableForGrandfatheredKeys() {
        ApiKey key = new ApiKey();
        key.setResponseMode(ResponseMode.FULL);

        assertThat(key.getEffectiveResponseMode()).isEqualTo(ResponseMode.FULL);
    }
}
