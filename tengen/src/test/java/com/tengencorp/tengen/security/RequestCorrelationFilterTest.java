package com.tengencorp.tengen.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesValidHeaderAndRestoresPreviousMdcContext() throws Exception {
        MDC.put("existing", "value");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "client.request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("client.request-42");
            assertThat(MDC.get("existing")).isEqualTo("value");
        });

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
            .isEqualTo("client.request-42");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
        assertThat(MDC.get("existing")).isEqualTo("value");
    }

    @Test
    void replacesInvalidHeaderAndCleansMdcAfterAnException() {
        MDC.put("existing", "value");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/events");
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "contains spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY))
                .matches("[A-Za-z0-9._-]{1,64}");
            throw new ServletException("expected");
        })).isInstanceOf(ServletException.class);

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
            .matches("[A-Za-z0-9._-]{1,64}")
            .isNotEqualTo("contains spaces");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
        assertThat(MDC.get("existing")).isEqualTo("value");
    }
}
