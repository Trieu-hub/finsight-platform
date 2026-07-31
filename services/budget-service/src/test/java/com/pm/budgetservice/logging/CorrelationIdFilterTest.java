package com.pm.budgetservice.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The correlation id is what ties one request's log lines together across services, so the two
 * failure modes that would silently break tracing are pinned here: an incoming id must survive
 * untouched (otherwise the chain restarts at every hop), and the MDC must be empty afterwards
 * (a leaked id would tag the next, unrelated request served by the same pooled thread).
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /** What the MDC held while the chain ran — by the time a test resumes the filter has cleared it. */
    private String idSeenByChain;

    private FilterChain capturingChain() {
        return (request, response) -> idSeenByChain = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
    }

    @Test
    void reusesAnIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "upstream-id-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(idSeenByChain).isEqualTo("upstream-id-42");
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo("upstream-id-42");
    }

    @Test
    void generatesAUuidWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(idSeenByChain).isNotNull();
        assertThatCode(() -> UUID.fromString(idSeenByChain)).doesNotThrowAnyException();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(idSeenByChain);
    }

    @Test
    void generatesAUuidWhenTheHeaderIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain());

        assertThat(idSeenByChain).isNotBlank();
        assertThatCode(() -> UUID.fromString(idSeenByChain)).doesNotThrowAnyException();
    }

    @Test
    void clearsTheMdcOnceTheRequestIsDone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "upstream-id-42");

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain());

        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }
}
