package com.qualityos.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesIdWhenNoneSupplied() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).hasSize(36);
    }

    @Test
    void reusesWellFormedIdAndExposesItToLogsDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-trace.0001");
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertThat(seenInChain).hasValue("client-trace.0001");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-trace.0001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "line\r\nbreak-injected-log", "has spaces in it"})
    void replacesMalformedIds(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, supplied);

        assertThat(run(request).getHeader(CorrelationIdFilter.HEADER)).isNotEqualTo(supplied).hasSize(36);
    }

    @Test
    void clearsMdcAfterRequestEvenWhenChainFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
        } catch (Exception expected) {
            // the assertion below is what matters
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }
}
