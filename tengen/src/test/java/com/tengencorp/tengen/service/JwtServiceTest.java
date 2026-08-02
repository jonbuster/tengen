package com.tengencorp.tengen.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
        "01234567890123456789012345678901", 15, 7, "tengen-test", "tengen-admin-test");

    @Test
    void accessAndRefreshTokensAreNotInterchangeable() {
        String access = jwtService.issueAccessToken("admin");
        String refresh = jwtService.issueRefreshToken("admin", jwtService.newTokenId());

        assertThat(jwtService.validateAccess(access).subject()).isEqualTo("admin");
        assertThat(jwtService.validateRefresh(refresh).subject()).isEqualTo("admin");
        assertThatThrownBy(() -> jwtService.validateRefresh(access))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtService.validateAccess(refresh))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
